(ns tatara.coordinator.github
  "GitHub as event source and status sink, without inbound webhooks.

  poll-once: for each configured repo, read the open PR heads and the default
  branch head; for every commit that carries `.tatara/pipeline.edn`, submit a
  run. Commits are remembered per process so their pipeline file is read once.
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

(defn- api [{:keys [api token]} method path & [body]]
  (let [res (http/request {:method method :uri (str api path) :throw false
                           :headers (cond-> {"accept" "application/vnd.github+json"
                                             "x-github-api-version" "2022-11-28"
                                             "authorization" (str "Bearer " token)}
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
    (concat (when (= 200 (:status main)) [{:ref (str "refs/heads/" branch) :sha (get-in main [:body :sha])}])
            (when (= 200 (:status pulls))
              (for [p (:body pulls)] {:ref (str "refs/pull/" (:number p) "/head") :sha (get-in p [:head :sha])})))))

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
  [server {:keys [api token max-snapshot-bytes] :as gh} repo sha]
  (let [res (http/request {:method :get :uri (str api "/repos/" repo "/tarball/" sha) :throw false
                           :as :bytes :follow-redirects :normal
                           :headers {"authorization" (str "Bearer " token)
                                     "accept" "application/vnd.github+json"}})
        data ^bytes (:body res)
        limit (or max-snapshot-bytes default-max-snapshot-bytes)]
    (cond
      (not= 200 (:status res)) (throw (ex-info "snapshot not readable" {:repo repo :sha sha :status (:status res)}))
      (> (count data) limit) (throw (ex-info "snapshot too large" {:repo repo :sha sha :bytes (count data)}))
      :else (srv/put-blob! server data))))

(defn poll-once
  "seen: atom #{[repo sha]}. Returns [{:repo :sha :reply}|{:repo :error}]."
  [server gh seen]
  (vec
   (for [repo (:repos gh)
         result (try
                  (vec (for [{:keys [ref sha]} (heads gh repo)
                             :when (and sha (not (contains? @seen [repo sha])))]
                         (let [p (pipeline-at gh repo sha)]
                           (swap! seen conj [repo sha])
                           (if (= :absent p)
                             {:repo repo :sha sha :reply :no-pipeline}
                             {:repo repo :sha sha
                              :reply (srv/submit! server {:event {:forge "github" :repo repo :ref ref :sha sha
                                                                  :pipeline-cid (c/cid crypto/ctx p)}
                                                          :pipeline p
                                                          :snapshot (snapshot! server gh repo sha)})}))))
                  (catch clojure.lang.ExceptionInfo e [{:repo repo :error (ex-data e)}]))]
     result)))

(defn deliver-once
  "Send pending commit statuses. Returns [{:key :status}]."
  [server gh]
  (vec
   (for [{k :key a :action} (svc/pending-actions @(:state server))
         :when (= :github-status (:kind a))]
     (let [r (api gh :post (str "/repos/" (:repo a) "/statuses/" (:sha a))
                  (cond-> {:state (:state a) :context (:context a) :description (:description a)}
                    (:target-base gh) (assoc :target_url (str (:target-base gh) "/v1/runs/" (:run-id a)))))]
       (when (<= 200 (:status r) 299) (srv/mark-delivered! server k))
       {:key k :status (:status r)}))))

(defn start-loops!
  "Run poll and delivery every :interval-ms on a daemon thread. Errors are
  printed and the loop continues."
  [server gh]
  (let [seen (atom #{})
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (try (doseq [r (poll-once server gh seen) :when (or (:error r) (map? (:reply r)))]
                              (println "poll" (pr-str r)))
                            (doseq [d (deliver-once server gh) :when (not (<= 200 (:status d) 299))]
                              (println "status delivery failed" (pr-str d)))
                            (catch Exception e (println "github loop error" (.getMessage e))))
                       (Thread/sleep (long (:interval-ms gh 60000)))
                       (recur))))]
    (.setDaemon t true) (.start t) t))
