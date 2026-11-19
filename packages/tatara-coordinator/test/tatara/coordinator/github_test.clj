(ns tatara.coordinator.github-test
  "T4 (AWAI-3408): poller and commit-status delivery against a fake GitHub API."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as hs]
            [tatara.coordinator.client :as cl]
            [tatara.coordinator.crypto :as crypto]
            [tatara.coordinator.e2e-test :as e2e]
            [tatara.coordinator.github :as gh]
            [tatara.coordinator.http :as srv]
            [tatara.coordinator.service :as svc])
  (:import (java.net.http HttpTimeoutException)
           (java.util Base64)))

(def repo "network-awai/example")
(def pipeline-text (pr-str e2e/pipeline))

(defn fake-github
  "files: {sha pipeline-text-or-nil}. fail-statuses: atom count of 500 replies."
  [files fail-statuses posted]
  (let [ok (fn [b] {:status 200 :headers {"content-type" "application/json"} :body (json/generate-string b)})
        handler (fn [{:keys [uri query-string request-method body]}]
                  (cond
                    (= uri (str "/repos/" repo)) (ok {:default_branch "main"})
                    (= uri (str "/repos/" repo "/commits/main")) (ok {:sha "main-sha"})
                    (= uri (str "/repos/" repo "/pulls")) (ok [{:number 7 :head {:sha "pr-sha"}}
                                                              {:number 8 :head {:sha "no-pipeline-sha"}}])
                    (= uri (str "/repos/" repo "/contents/.tatara/pipeline.edn"))
                    (let [sha (second (re-find #"ref=([^&]+)" (or query-string "")))]
                      (if-let [t (get files sha)]
                        (ok {:content (.encodeToString (Base64/getMimeEncoder) (.getBytes ^String t "UTF-8"))})
                        {:status 404 :body "{}"}))
                    (str/starts-with? uri (str "/repos/" repo "/tarball/"))
                    {:status 200 :headers {"content-type" "application/gzip"}
                     :body (str "tarball of " (last (str/split uri #"/")))}
                    (and (= :post request-method) (str/starts-with? uri (str "/repos/" repo "/statuses/")))
                    (if (pos? @fail-statuses)
                      (do (swap! fail-statuses dec) {:status 500 :body "{}"})
                      (do (swap! posted conj (assoc (json/parse-string (slurp body) true)
                                                    :sha (last (str/split uri #"/"))))
                          {:status 201 :body "{}"}))
                    :else {:status 404 :body "{}"}))
        s (hs/run-server handler {:port 0 :legacy-return-value? false})]
    {:stop #(hs/server-stop! s) :api (str "http://127.0.0.1:" (hs/server-port s))}))

(deftest poll-submit-run-and-deliver-statuses
  (let [root (str (fs/create-temp-dir {:prefix "tatara-gh-"}))
        posted (atom []) fails (atom 1)
        fake (fake-github {"main-sha" pipeline-text "pr-sha" pipeline-text} fails posted)
        s (srv/start! {:root root :allow #{(:did e2e/a) (:did e2e/b)}} {:clock e2e/clock})
        g {:api (:api fake) :token "test-token" :repos [repo]}
        seen (atom #{})]
    (try
      (let [first-poll (gh/poll-once s g seen)]
        (testing "commits with a pipeline become runs; others are skipped"
          (is (= 2 (count (filter #(map? (:reply %)) first-poll))))
          (is (some #(= :no-pipeline (:reply %)) first-poll))
          (is (= 2 (count (:runs @(:state s)))))
          (is (every? #(= 64 (count (get-in % [:snapshot :sha256]))) (vals (:runs @(:state s)))))))
      (testing "a second poll does not resubmit"
        (is (empty? (gh/poll-once s g seen))))
      (testing "a failed status post stays pending and is retried"
        (let [d1 (gh/deliver-once s g)]
          (is (some #(= 500 (:status %)) d1))
          (is (= 1 (count (svc/pending-actions @(:state s)))))
          (gh/deliver-once s g)
          (is (empty? (svc/pending-actions @(:state s))))
          (is (= #{"main-sha" "pr-sha"} (set (map :sha @posted))))
          (is (every? #(and (= "pending" (:state %)) (= "tatara/ci" (:context %))) @posted))))
      (testing "the quorum result is posted as success"
        (let [ca (cl/client (str "http://127.0.0.1:" (:port s)) e2e/a e2e/clock)
              cb (cl/client (str "http://127.0.0.1:" (:port s)) e2e/b e2e/clock)
              ja (:body (cl/lease ca [:t2])) jb (:body (cl/lease cb [:t2]))]
          (is (= (:run-id ja) (:run-id jb)))
          (e2e/do-job ca ja "same") (e2e/do-job cb jb "same")
          (reset! posted [])
          (gh/deliver-once s g)
          (is (= [["success" (:sha ja)]] (mapv (juxt :state :sha) @posted)))))
      (testing "delivered actions are persisted"
        (srv/stop! s)
        (let [s2 (srv/start! {:root root :allow #{}} {:clock e2e/clock})]
          (is (empty? (svc/pending-actions @(:state s2))))
          (srv/stop! s2)))
      (finally ((:stop fake)) (fs/delete-tree root)))))

(deftest a-run-finished-before-delivery-posts-only-its-result
  (testing "pending is not sent after success when both are queued in one round (AWAI-3542)"
    (let [root (str (fs/create-temp-dir {:prefix "tatara-gh-order-"}))
          posted (atom []) fake (fake-github {"main-sha" pipeline-text} (atom 0) posted)
          s (srv/start! {:root root :allow #{(:did e2e/a) (:did e2e/b)}} {:clock e2e/clock})
          g {:api (:api fake) :token "t" :repos [repo]}]
      (try
        (gh/poll-once s g (atom #{}))
        (let [ca (cl/client (str "http://127.0.0.1:" (:port s)) e2e/a e2e/clock)
              cb (cl/client (str "http://127.0.0.1:" (:port s)) e2e/b e2e/clock)
              ja (:body (cl/lease ca [:t2])) jb (:body (cl/lease cb [:t2]))]
          (e2e/do-job ca ja "same") (e2e/do-job cb jb "same")
          (is (= 2 (count (svc/pending-actions @(:state s)))))
          (let [d (gh/deliver-once s g)]
            (is (= 1 (count (filter :skipped d)))))
          (is (= [["success" "main-sha"]] (mapv (juxt :state :sha) @posted)))
          (is (empty? (svc/pending-actions @(:state s)))))
        (finally (srv/stop! s) ((:stop fake)) (fs/delete-tree root))))))

(deftest the-background-loop-polls-and-delivers
  (testing "start-loops! runs a poll and a delivery round (AWAI-3411: it once called poll-once with too few args)"
    (let [root (str (fs/create-temp-dir {:prefix "tatara-gh-loop-"}))
          posted (atom []) fake (fake-github {"main-sha" pipeline-text} (atom 0) posted)
          s (srv/start! {:root root :allow #{}} {:clock e2e/clock})]
      (let [threads (gh/start-loops! s {:api (:api fake) :token "t" :repos [repo] :interval-ms 100})]
        (try
          (loop [i 0] (when (and (< i 100) (empty? @posted)) (Thread/sleep 100) (recur (inc i))))
          (is (= 1 (count (:runs @(:state s)))))
          (is (= ["pending"] (mapv :state @posted)))
          (finally
            (doseq [t threads] (.interrupt t) (.join t 1000))
            (srv/stop! s) ((:stop fake)) (fs/delete-tree root)))))))

(deftest a-timed-out-snapshot-is-retried-without-skipping-later-heads
  (let [seen (atom #{})
        stalled? (atom true)
        submitted (atom [])
        server {}
        g {:repos [repo "another/repo"]}]
    (with-redefs [gh/heads (fn [_ r] (if (= r repo)
                                      [{:ref "refs/heads/main" :sha "first"}
                                       {:ref "refs/pull/1/head" :sha "second"}]
                                      [{:ref "refs/heads/main" :sha "other"}]))
                  gh/pipeline-at (constantly e2e/pipeline)
                  gh/snapshot! (fn [_ _ _ sha]
                                 (when (and (= "first" sha) (compare-and-set! stalled? true false))
                                   (throw (HttpTimeoutException. "test snapshot timeout"))))
                  srv/submit! (fn [_ {:keys [event]}]
                                (swap! submitted conj (:sha event))
                                {:run-id (:sha event)})]
      (let [results (gh/poll-once server g seen)]
        (is (= :snapshot (get-in (first results) [:error :phase])))
        (is (= ["second" "other"] @submitted))
        (is (not (contains? @seen [repo "first"]))))
      (gh/poll-once server g seen)
      (is (= ["second" "other" "first"] @submitted))
      (is (empty? (gh/poll-once server g seen))))))

(deftest a-stalled-repository-does-not-block-other-repos-or-delivery
  (let [stalled-repo "network-awai/stalled"
        entered (promise)
        release (promise)
        delivered (promise)
        admitted (promise)]
    (with-redefs [gh/heads (fn [_ r]
                            (if (= stalled-repo r)
                              (do (deliver entered true) @release [])
                              [{:ref "refs/pull/7/head" :sha "pr-sha"}]))
                  gh/pipeline-at (constantly e2e/pipeline)
                  gh/snapshot! (constantly nil)
                  srv/submit! (fn [_ data] (deliver admitted (:event data)) {:run-id "test"})
                  gh/deliver-once (fn [_ _] (deliver delivered true) [])]
      (let [threads (gh/start-loops! {} {:repos [stalled-repo repo]})]
        (try
          (is (= true (deref entered 5000 false)))
          (is (= [repo "pr-sha"] ((juxt :repo :sha) (deref admitted 5000 {}))))
          (is (= true (deref delivered 5000 false)))
          (finally
            (deliver release true)
            (doseq [t threads] (.interrupt t) (.join t 1000))))))))

(deftest unreadable-pull-requests-are-an-error-not-silence
  (testing "a 403 on the PR list (private repo, lease without pull_requests: read) is reported"
    (let [s (hs/run-server (fn [{:keys [uri]}]
                             (cond (= uri (str "/repos/" repo)) {:status 200 :body (json/generate-string {:default_branch "main"})}
                                   (= uri (str "/repos/" repo "/commits/main")) {:status 200 :body (json/generate-string {:sha "main-sha"})}
                                   :else {:status 403 :body (json/generate-string {:message "Resource not accessible by integration"})}))
                           {:port 0 :legacy-return-value? false})
          g {:api (str "http://127.0.0.1:" (hs/server-port s)) :token "t" :repos [repo]}]
      (try
        (let [e (try (gh/heads g repo) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (= 403 (:status (ex-data e)))))
        (let [srv (srv/start! {:root (str (fs/create-temp-dir {:prefix "tatara-gh-"})) :allow #{}} {:clock e2e/clock})]
          (try (let [[result] (gh/poll-once srv g (atom #{}))]
                 (is (= repo (:repo result)))
                 (is (= 403 (get-in result [:error :status]))))
               (finally (srv/stop! srv))))
        (finally (hs/server-stop! s))))))

