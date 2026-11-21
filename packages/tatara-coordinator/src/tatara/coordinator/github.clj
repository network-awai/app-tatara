(ns tatara.coordinator.github
  "GitHub as event source and status sink, without inbound webhooks.

  poll-once: for each configured repo, read the open PR heads and the default
  branch head; for every commit that carries `.tatara/pipeline.edn`, submit a
  run of the default branch head's pipeline (the trusted one, AWAI-3799). Commits are remembered per process after admission or a missing pipeline.
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

(defn- interrupt? [^Throwable e]
  (loop [e e]
    (cond (nil? e) false
          (instance? InterruptedException e) true
          :else (recur (.getCause e)))))

(defn- unless-interrupt
  "Rethrow e when it is (or wraps) an interrupt, so a stopped worker stops
  instead of recording the interrupt as a GitHub failure (AWAI-3820)."
  [^Throwable e]
  (when (interrupt? e) (throw (InterruptedException. "interrupted")))
  e)

(defn- failure [phase e]
  (merge {:phase phase :type (str (class e))}
         (select-keys (ex-data e) [:status :bytes])))

(defn- poll-head
  "trusted: a delay of the pipeline at the default branch head (or :absent).
  A head opts in by carrying .tatara/pipeline.edn, but what runs is always the
  trusted pipeline, never the head's own file: a PR cannot change its
  steps, :network, :tier or toolchain by editing .tatara/pipeline.edn
  (AWAI-3799). A PR's own pipeline file takes effect once it is merged."
  [server gh seen repo trusted {:keys [ref sha]}]
  (let [key [repo sha]
        result {:repo repo :sha sha}]
    (try
      (let [pipeline (if (= :absent (pipeline-at gh repo sha)) :absent @trusted)
            reply (if (= :absent pipeline)
                    :no-pipeline
                    (srv/submit! server
                                 {:event {:forge "github" :repo repo :ref ref :sha sha
                                          :pipeline-cid (c/cid crypto/ctx pipeline)}
                                  :pipeline pipeline
                                  :snapshot (try (snapshot! server gh repo sha)
                                                 (catch Exception e
                                                   (unless-interrupt e)
                                                   (throw (ex-info "snapshot failed"
                                                                   (failure :snapshot e) e))))}))]
        (when (or (= :no-pipeline reply) (:run-id reply))
          (swap! seen conj key))
        (assoc result :reply reply))
      (catch Exception e
        (unless-interrupt e)
        (assoc result :error (if (= :snapshot (:phase (ex-data e)))
                               (ex-data e)
                               (failure :admission e)))))))

(defn- poll-repo [server gh seen repo]
  (try
    (let [hs (heads gh repo)
          main-sha (some #(when (re-find #"^refs/heads/" (:ref %)) (:sha %)) hs)
          ;; No readable default branch head: nothing is trusted, nothing runs.
          trusted (delay (if main-sha (pipeline-at gh repo main-sha) :absent))]
      (vec (for [{:keys [sha] :as head} hs
                 :when (and sha (not (contains? @seen [repo sha])))]
             (poll-head server gh seen repo trusted head))))
    (catch Exception e
      (unless-interrupt e)
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
             (unless-interrupt e)
             {:key k :repo (:repo a) :sha (:sha a) :error (failure :status e)})))))))

;; ── merge gate (AWAI-3574) ──────────────────────────────────────────────────
;; GitHub's branch protection is not available for the private repositories on
;; the current plan, so Tatara itself merges: a PR that carries the opt-in label
;; is squash-merged once THIS coordinator's own quorum passed for its exact head
;; SHA. The decision reads the coordinator state, not GitHub's commit status,
;; which anything with statuses:write could set. The merge call carries the SHA,
;; so GitHub refuses it if the head moved after verification.

(def default-merge-label "tatara:merge")

(defn sha-verdict
  "What this coordinator decided for repo@sha: :passed when at least one run
  reached quorum on a passed verdict and none failed or hard-errored; :failed
  when any run failed or hard-errored; :pending when runs exist but none
  finished; :unknown when there is no run."
  [state repo sha]
  (let [runs (filter #(and (= repo (:repo %)) (= sha (:sha %))) (vals (:runs state)))
        result (fn [r] (when (= :reached (:status r))
                         (let [vc (get-in r [:outcome :verdict-cid])]
                           (some #(when (= vc (:verdict-cid %)) (:result %)) (:entries r)))))]
    (cond
      (empty? runs) :unknown
      (some #(or (= :hard-error (:status %)) (and (= :reached (:status %)) (not= :passed (result %)))) runs) :failed
      (some #(= :passed (result %)) runs) :passed
      :else :pending)))

(defn merge-once
  "For each repo in (:merge gh) :repos, merge the open, non-draft PRs that carry
  the label and whose head this coordinator verified. Returns one entry per
  labelled PR: {:repo :number :sha :outcome :merged|:waiting|:blocked|:not-mergeable|:error}."
  [server gh]
  (let [{:keys [repos label method] :or {label default-merge-label method "squash"}} (:merge gh)
        mgh (merge gh (select-keys (:merge gh) [:token :token-fn]))
        st (some-> (:state server) deref)]
    (vec
     (for [repo (distinct repos)
           :let [pulls (try (api mgh :get (str "/repos/" repo "/pulls?state=open&per_page=100"))
                            (catch Exception e (unless-interrupt e) {:error (failure :merge-list e)}))]
           p (if (= 200 (:status pulls)) (:body pulls) [{::list-failed pulls}])
           :let [failed (::list-failed p)]
           :when (or failed (and (not (:draft p)) (some #(= label (:name %)) (:labels p))))]
       (if failed
         {:repo repo :outcome :error :error (or (:error failed) {:phase :merge-list :status (:status failed)})}
         (let [n (:number p) sha (get-in p [:head :sha]) v (sha-verdict st repo sha)
               base {:repo repo :number n :sha sha :verdict v}]
           (case v
             :passed (try
                       (let [r (api mgh :put (str "/repos/" repo "/pulls/" n "/merge")
                                    {:merge_method method :sha sha})]
                         (cond (= 200 (:status r)) (assoc base :outcome :merged :merge-sha (get-in r [:body :sha]))
                               (#{405 409} (:status r)) (assoc base :outcome :not-mergeable :status (:status r)
                                                              :message (get-in r [:body :message]))
                               :else (assoc base :outcome :error :status (:status r))))
                       (catch Exception e (unless-interrupt e) (assoc base :outcome :error :error (failure :merge e))))
             :failed (assoc base :outcome :blocked)
             (assoc base :outcome :waiting))))))))

(defn- start-worker!
  "Run tick every interval-ms on a daemon thread until the thread is
  interrupted. An interrupt that lands inside tick (an HTTP call, a blocked
  deref) ends the worker too: catching it as an ordinary failure kept stopped
  workers running into later tests (AWAI-3820)."
  [label interval-ms tick]
  (doto (Thread. ^Runnable
                 (fn []
                   (try
                     (loop []
                       (when-not (.isInterrupted (Thread/currentThread))
                         (try (tick)
                              (catch Throwable e
                                (if (interrupt? e)
                                  (.interrupt (Thread/currentThread))
                                  (println label (pr-str (failure :worker e))))))
                         (when-not (.isInterrupted (Thread/currentThread))
                           (Thread/sleep (long interval-ms))
                           (recur))))
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
    (cond->
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
                       (println "status delivery failed" (pr-str d)))))
     ;; Merge gate only when configured (AWAI-3574).
     (:merge gh)
     (conj (start-worker! "tatara-github-merge" (get-in gh [:merge :interval-ms] interval-ms)
                          #(doseq [m (merge-once server gh)
                                   :when (#{:merged :not-mergeable :error} (:outcome m))]
                             (println "merge" (pr-str m))))))))
