(ns tatara.coordinator.github
  "GitHub as event source and status sink, without inbound webhooks.

  poll-once: for each configured repo, read the open PR heads and the default
  branch head; for every commit that carries `.tatara/pipeline.edn`, submit a
  run. Commits are remembered per process after admission or a missing pipeline.
  deliver-once: send each pending outbox action as a commit status and mark it
  delivered only after GitHub answers 2xx; failures stay pending for the next
  round. The token stays in the coordinator process; runners never see it."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [tatara.core.canonical :as c]
            [tatara.coordinator.crypto :as crypto]
            [tatara.coordinator.http :as srv]
            [tatara.coordinator.service :as svc])
  (:import (java.util Base64)))

(def pipeline-path ".tatara/pipeline.edn")
(def default-max-snapshot-bytes (* 2 1024 1024 1024))
(def max-pipeline-bytes 65536)

;; Bound each GitHub request so the background workers can retry.
(def ^:private client
  (delay (http/client {:connect-timeout 10000 :follow-redirects :normal})))
(def api-timeout-ms 30000)
(def snapshot-timeout-ms 600000)

(defn token-of
  "The GitHub token: a lease from kagitaba-minter (:token-fn) or, for local
  use only, a fixed :token."
  [{:keys [token token-fn]}]
  (if token-fn (token-fn) token))

(defn- api [{:keys [api] :as gh} method path & [body]]
  (let [res (http/request {:method method :uri (str api path) :throw false
                           :client @client :timeout (:timeout-ms gh api-timeout-ms)
                           :headers (cond-> {"accept" "application/vnd.github+json"
                                             "x-github-api-version" "2022-11-28"
                                             "authorization" (str "Bearer " (token-of gh))}
                                      body (assoc "content-type" "application/json"))
                           :body (when body (json/generate-string body))})]
    {:status (:status res)
     :body (when (seq (:body res)) (try (json/parse-string (:body res) true) (catch Exception _ nil)))}))

(defn heads
  "[{:ref :sha}] for the default branch and every open PR head of repo."
  [gh repo]
  (let [info (api gh :get (str "/repos/" repo))
        branch (get-in info [:body :default_branch])
        main (when branch (api gh :get (str "/repos/" repo "/commits/" branch)))
        pulls (api gh :get (str "/repos/" repo "/pulls?state=open&per_page=100"))]
    (when-not (= 200 (:status info)) (throw (ex-info "repo not readable" {:repo repo :status (:status info)})))
    ;; A private repo needs pull_requests: read in the lease. Without it the
    ;; PR list is 403; say so instead of silently testing main only (AWAI-3411).
    (when-not (= 200 (:status pulls))
      (throw (ex-info "pull requests not readable" {:repo repo :status (:status pulls)})))
    (concat (when (= 200 (:status main)) [{:ref (str "refs/heads/" branch) :sha (get-in main [:body :sha])}])
            (for [p (:body pulls)] {:ref (str "refs/pull/" (:number p) "/head") :sha (get-in p [:head :sha])}))))

(defn pipeline-at
  "The pipeline map at sha, :absent, or throws on malformed content."
  [gh repo sha]
  (let [r (api gh :get (str "/repos/" repo "/contents/" pipeline-path "?ref=" sha))]
    (cond
      (= 404 (:status r)) :absent
      (not= 200 (:status r)) (throw (ex-info "pipeline not readable" {:repo repo :sha sha :status (:status r)}))
      :else (let [raw (.decode (Base64/getMimeDecoder) ^String (get-in r [:body :content]))]
              (when (> (count raw) max-pipeline-bytes) (throw (ex-info "pipeline too large" {:repo repo :sha sha})))
              (let [p (edn/read-string (String. raw "UTF-8"))]
                (when-not (map? p) (throw (ex-info "pipeline is not a map" {:repo repo :sha sha})))
                p)))))

(defn snapshot!
  "Fetch the commit tarball once and admit it to the CAS. Runners download it
  from the coordinator with their lease, so the GitHub token stays here."
  [server {:keys [api max-snapshot-bytes] :as gh} repo sha]
  (let [res (http/request {:method :get :uri (str api "/repos/" repo "/tarball/" sha) :throw false
                           :as :bytes :client @client :timeout snapshot-timeout-ms
                           :headers {"authorization" (str "Bearer " (token-of gh))
                                     "accept" "application/vnd.github+json"}})
        data ^bytes (:body res)
        limit (or max-snapshot-bytes default-max-snapshot-bytes)]
    (cond
      (not= 200 (:status res)) (throw (ex-info "snapshot not readable" {:repo repo :sha sha :status (:status res)}))
      (> (count data) limit) (throw (ex-info "snapshot too large" {:repo repo :sha sha :bytes (count data)}))
      :else (srv/put-blob! server data))))

(defn- failure [phase e]
  (merge {:phase phase :type (str (class e))}
         (select-keys (ex-data e) [:status :bytes])))

(defn- poll-head [server gh seen repo {:keys [ref sha]}]
  (let [key [repo sha]
        result {:repo repo :sha sha}]
    (try
      (let [pipeline (pipeline-at gh repo sha)
            reply (if (= :absent pipeline)
                    :no-pipeline
                    (srv/submit! server
                                 {:event {:forge "github" :repo repo :ref ref :sha sha
                                          :pipeline-cid (c/cid crypto/ctx pipeline)}
                                  :pipeline pipeline
                                  :snapshot (try (snapshot! server gh repo sha)
                                                 (catch Exception e
                                                   (throw (ex-info "snapshot failed"
                                                                   (failure :snapshot e) e))))}))]
        (when (or (= :no-pipeline reply) (:run-id reply))
          (swap! seen conj key))
        (assoc result :reply reply))
      (catch Exception e
        (assoc result :error (if (= :snapshot (:phase (ex-data e)))
                               (ex-data e)
                               (failure :admission e)))))))

(defn- poll-repo [server gh seen repo]
  (try
    (vec (for [{:keys [sha] :as head} (heads gh repo)
               :when (and sha (not (contains? @seen [repo sha])))]
           (poll-head server gh seen repo head)))
    (catch Exception e
      [{:repo repo :error (failure :heads e)}])))

(defn poll-once
  "Poll configured repositories once. Failed heads remain eligible for retry."
  [server gh seen]
  (into [] (mapcat #(poll-repo server gh seen %)) (:repos gh)))

(defn- finished-runs
  "Run ids that already have a final (non-pending) status action, delivered or not."
  [state]
  (->> (vals (get-in state [:outbox :actions]))
       (filter #(and (= :github-status (:kind %)) (not= "pending" (:state %))))
       (map :run-id)
       set))

(defn deliver-once
  "Send pending commit statuses. Returns [{:key :status}].
  A pending status of a run that already has a final one is marked delivered
  without being sent ({:key :skipped :superseded}). Otherwise, when both are
  queued in one round, the pending one could reach GitHub last and hide the result."
  [server gh]
  (let [st @(:state server)
        done (finished-runs st)]
    (vec
     (for [{k :key a :action} (svc/pending-actions st)
           :when (= :github-status (:kind a))]
       (if (and (= "pending" (:state a)) (contains? done (:run-id a)))
         (do (srv/mark-delivered! server k) {:key k :skipped :superseded})
         (try
           (let [r (api gh :post (str "/repos/" (:repo a) "/statuses/" (:sha a))
                        (cond-> {:state (:state a) :context (:context a) :description (:description a)}
                          (:target-base gh) (assoc :target_url (str (:target-base gh) "/v1/runs/" (:run-id a)))))]
             (when (<= 200 (:status r) 299) (srv/mark-delivered! server k))
             {:key k :status (:status r)})
           (catch Exception e
             {:key k :repo (:repo a) :sha (:sha a) :error (failure :status e)})))))))

(defn- start-worker! [label interval-ms tick]
  (doto (Thread. ^Runnable
                 (fn []
                   (try
                     (loop []
                       (try (tick)
                            (catch Exception e
                              (println label (pr-str (failure :worker e)))))
                       (Thread/sleep (long interval-ms))
                       (recur))
                     (catch InterruptedException _)))
                 label)
    (.setDaemon true)
    (.start)))

(defn start-loops!
  "Poll each configured repository independently and drain the outbox on a
  separate daemon thread. Return the worker threads."
  [server gh]
  (let [interval-ms (:interval-ms gh 60000)
        poll-interval-ms (:poll-interval-ms gh interval-ms)]
    (conj
     (mapv (fn [repo]
             (let [seen (atom #{})]
               (start-worker! (str "tatara-github-poll-" repo) poll-interval-ms
                              #(doseq [r (poll-repo server gh seen repo)
                                       :when (or (:error r) (map? (:reply r)))]
                                 (println "poll" (pr-str r))))))
           (distinct (:repos gh)))
     (start-worker! "tatara-github-delivery" interval-ms
                    #(doseq [{:keys [status error] :as d} (deliver-once server gh)
                             :when (or error (and status (not (<= 200 status 299))))]
                       (println "status delivery failed" (pr-str d)))))))
