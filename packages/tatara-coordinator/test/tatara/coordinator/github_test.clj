(ns tatara.coordinator.github-test
  "T4 (AWAI-3408): poller and commit-status delivery against a fake GitHub API."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as hs]
            [tatara.core.canonical :as c]
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
                              [{:ref "refs/heads/main" :sha "main-sha"} {:ref "refs/pull/7/head" :sha "pr-sha"}]))
                  gh/pipeline-at (constantly e2e/pipeline)
                  gh/snapshot! (constantly nil)
                  srv/submit! (fn [_ data] (when (= "pr-sha" (get-in data [:event :sha])) (deliver admitted (:event data))) {:run-id "test"})
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


;; ── merge gate (AWAI-3574) ─────────────────────────────────────────────────

(defn- fake-merge-github
  "pulls: vector of PR maps. merge-replies: atom {number status}. merged: atom of requests."
  [pulls merge-replies merged]
  (let [handler (fn [{:keys [uri request-method body]}]
                  (cond
                    (and (= :get request-method) (= uri (str "/repos/" repo "/pulls")))
                    {:status 200 :body (json/generate-string pulls)}
                    (and (= :put request-method) (re-matches #".*/pulls/\d+/merge" uri))
                    (let [n (parse-long (second (re-find #"/pulls/(\d+)/merge" uri)))
                          req (json/parse-string (slurp body) true)
                          st (get @merge-replies n 200)]
                      (swap! merged conj (assoc req :number n))
                      {:status st :body (json/generate-string (if (= 200 st) {:merged true :sha (str "merge-" n)} {:message "Head branch was modified"}))})
                    :else {:status 404 :body "{}"}))
        s (hs/run-server handler {:port 0 :legacy-return-value? false})]
    {:stop #(hs/server-stop! s) :api (str "http://127.0.0.1:" (hs/server-port s))}))

(defn- run-of [sha status result]
  (let [vc (str "v-" sha)]
    {:repo repo :sha sha :status status
     :entries [{:runner "a" :verdict-cid vc :result result} {:runner "b" :verdict-cid vc :result result}]
     :outcome (when (= :reached status) {:status :reached :verdict-cid vc})}))

(def label [{:name "tatara:merge"}])

(deftest merge-gate-merges-only-labelled-verified-heads
  (let [merged (atom []) replies (atom {5 409})
        pulls [{:number 1 :head {:sha "ok"} :labels label}
               {:number 2 :head {:sha "ok-unlabelled"} :labels []}
               {:number 3 :head {:sha "failed"} :labels label}
               {:number 4 :head {:sha "running"} :labels label}
               {:number 5 :head {:sha "moved"} :labels label}
               {:number 6 :head {:sha "ok-draft"} :labels label :draft true}
               {:number 7 :head {:sha "never-run"} :labels label}
               {:number 8 :head {:sha "hard"} :labels label}]
        fake (fake-merge-github pulls replies merged)
        state {:runs {"r1" (run-of "ok" :reached :passed)
                      "r2" (run-of "ok-unlabelled" :reached :passed)
                      "r3" (run-of "failed" :reached :failed)
                      "r4" {:repo repo :sha "running" :status :pending :entries []}
                      "r5" (run-of "moved" :reached :passed)
                      "r6" (run-of "ok-draft" :reached :passed)
                      "r8" {:repo repo :sha "hard" :status :hard-error :entries []}}}
        server {:state (atom state)}
        g {:api (:api fake) :token "read-only" :merge {:repos [repo] :token "merge-token"}}]
    (try
      (let [out (into {} (map (juxt :number identity)) (gh/merge-once server g))]
        (testing "labelled + passed quorum is squash-merged with the verified SHA as a guard"
          (is (= :merged (get-in out [1 :outcome])))
          (is (= {:merge_method "squash" :sha "ok" :number 1} (first (filter #(= 1 (:number %)) @merged)))))
        (testing "unlabelled and draft PRs are ignored, even when verified"
          (is (not (contains? out 2))) (is (not (contains? out 6))))
        (testing "a failed or hard-errored quorum blocks; pending and missing runs wait"
          (is (= :blocked (get-in out [3 :outcome]))) (is (= :blocked (get-in out [8 :outcome])))
          (is (= :waiting (get-in out [4 :outcome]))) (is (= :waiting (get-in out [7 :outcome]))))
        (testing "GitHub refusing the SHA guard (head moved) is reported, not retried as success"
          (is (= :not-mergeable (get-in out [5 :outcome])))
          (is (= 409 (get-in out [5 :status]))))
        (testing "only the passed heads reached the merge endpoint"
          (is (= #{1 5} (set (map :number @merged))))))
      (finally ((:stop fake))))))

(deftest merge-gate-reads-the-coordinator-not-the-github-status
  (testing "a success status set by someone else does not make a head mergeable"
    (is (= :unknown (gh/sha-verdict {:runs {}} repo "forged")))
    (is (= :failed (gh/sha-verdict {:runs {"a" (run-of "x" :reached :passed) "b" (run-of "x" :reached :failed)}} repo "x")))
    (is (= :passed (gh/sha-verdict {:runs {"a" (run-of "x" :reached :passed)}} repo "x")))
    (is (= :unknown (gh/sha-verdict {:runs {"a" (run-of "x" :reached :passed)}} "other/repo" "x")))))

(deftest no-merge-config-no-merge-worker
  (with-redefs [gh/heads (fn [& _] []) gh/deliver-once (fn [& _] [])]
    (let [t1 (gh/start-loops! {} {:repos [repo] :interval-ms 1000})
          t2 (gh/start-loops! {} {:repos [repo] :interval-ms 1000 :merge {:repos [repo]}})]
      (try
        (is (= 2 (count t1)))
        (is (= 3 (count t2)))
        (is (some #(= "tatara-github-merge" (.getName ^Thread %)) t2))
        (finally (doseq [t (concat t1 t2)] (.interrupt ^Thread t) (.join ^Thread t 2000)))))))

(deftest a-pull-request-runs-the-default-branch-pipeline
  (testing "a PR that edits .tatara/pipeline.edn (steps, :fetch) still runs main's pipeline (AWAI-3799)"
    (let [evil (assoc e2e/pipeline :network :fetch :steps [["curl" "http://100.100.100.100"]])
          submitted (atom [])]
      (with-redefs [gh/heads (fn [_ _] [{:ref "refs/heads/main" :sha "main-sha"}
                                        {:ref "refs/pull/9/head" :sha "evil-sha"}])
                    gh/pipeline-at (fn [_ _ sha] (if (= "evil-sha" sha) evil e2e/pipeline))
                    gh/snapshot! (constantly nil)
                    srv/submit! (fn [_ body] (swap! submitted conj body) {:run-id (get-in body [:event :sha])})]
        (gh/poll-once {} {:repos [repo]} (atom #{}))
        (is (= ["main-sha" "evil-sha"] (mapv #(get-in % [:event :sha]) @submitted)))
        (is (every? #(= e2e/pipeline (:pipeline %)) @submitted))
        (is (every? #(= (c/cid crypto/ctx e2e/pipeline) (get-in % [:event :pipeline-cid])) @submitted)))))
  (testing "without a readable default branch head nothing runs"
    (let [submitted (atom [])]
      (with-redefs [gh/heads (fn [_ _] [{:ref "refs/pull/9/head" :sha "pr-only"}])
                    gh/pipeline-at (constantly e2e/pipeline)
                    gh/snapshot! (constantly nil)
                    srv/submit! (fn [_ body] (swap! submitted conj body) {:run-id "x"})]
        (is (= [:no-pipeline] (mapv :reply (gh/poll-once {} {:repos [repo]} (atom #{})))))
        (is (empty? @submitted))))))

(deftest an-interrupt-inside-a-tick-stops-the-worker
  (testing "workers stop even when interrupted in the middle of a blocking call (AWAI-3820)"
    (let [blocked (promise) never (promise)]
      (with-redefs [gh/heads (fn [_ _] (deliver blocked true) @never [])
                    gh/deliver-once (fn [_ _] [])]
        (let [threads (gh/start-loops! {} {:repos [repo] :interval-ms 50})]
          (is (= true (deref blocked 5000 false)))
          (doseq [t threads] (.interrupt ^Thread t) (.join ^Thread t 5000))
          (is (every? #(not (.isAlive ^Thread %)) threads)))))))
