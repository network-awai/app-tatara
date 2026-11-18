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
  (:import (java.util Base64)))

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

(deftest the-background-loop-polls-and-delivers
  (testing "start-loops! runs a poll and a delivery round (AWAI-3411: it once called poll-once with too few args)"
    (let [root (str (fs/create-temp-dir {:prefix "tatara-gh-loop-"}))
          posted (atom []) fake (fake-github {"main-sha" pipeline-text} (atom 0) posted)
          s (srv/start! {:root root :allow #{}} {:clock e2e/clock})]
      (try
        (gh/start-loops! s {:api (:api fake) :token "t" :repos [repo] :interval-ms 600000})
        (loop [i 0] (when (and (< i 100) (empty? @posted)) (Thread/sleep 100) (recur (inc i))))
        (is (= 1 (count (:runs @(:state s)))))
        (is (= ["pending"] (mapv :state @posted)))
        (finally (srv/stop! s) ((:stop fake)) (fs/delete-tree root))))))
