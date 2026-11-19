(ns tatara.coordinator.e2e-test
  "T3 acceptance (AWAI-3407): a real HTTP coordinator with durable state and two
  runners with different DIDs. Failure modes from ADR-0001 each have a case:
  duplicate events, one DID taking two replicas, stale and expired leases,
  missing CAS objects, split verdicts, unknown runners, forged signatures and
  coordinator restart."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [tatara.core.canonical :as c]
            [tatara.coordinator.client :as cl]
            [tatara.coordinator.crypto :as crypto]
            [tatara.coordinator.http :as srv]
            [tatara.coordinator.service :as svc]
            [tatara.core.verify :as vf]))

(def now (atom 1700000000000))
(defn clock [] @now)

(def a (crypto/generate))
(def b (crypto/generate))
(def stranger (crypto/generate))
(def admin "test-admin-token")

(def pipeline {:tier :t2 :network :none :steps [["make" "test"]] :artifacts ["out"]})
(def event {:forge "github" :repo "network-awai/example" :ref "refs/pull/1/head"
            :sha "0123456789abcdef0123456789abcdef01234567" :pipeline-cid (c/cid crypto/ctx pipeline)})

(def ^:dynamic *root* nil)

(defn start [] (srv/start! {:root *root* :allow #{(:did a) (:did b)} :ttl-ms 60000}
                           {:clock clock :admin-token admin}))

(defn base [s] (str "http://127.0.0.1:" (:port s)))

(defn submit [s body]
  (let [r (http/post (str (base s) "/v1/runs")
                     {:headers {"authorization" (str "Bearer " admin)} :body (pr-str body) :throw false})]
    {:status (:status r) :body (edn/read-string (:body r))}))

(defn get-run [s id] (edn/read-string (:body (http/get (str (base s) "/v1/runs/" id)))))

(defn do-job
  "Upload an artifact, the verdict, a receipt and a snapshot; then complete."
  [c job ^String artifact & {:keys [skip-receipt?]}]
  (let [blob (cl/put-blob c job (.getBytes artifact "UTF-8"))
        art (cl/put-object c job {:tatara/blob (:sha blob) :size (count artifact)})
        verdict {:source (:sha job) :pipeline-cid (:pipeline-cid job) :result :passed
                 :artifacts [{:name "out" :cid (get-in art [:body :cid])}]}
        vobj (cl/put-object c job verdict)
        receipt (if skip-receipt? {:body {:cid "cid:sha256:00"}} (cl/put-object c job {:job-id (:job-id job) :steps [{:argv ["make" "test"] :exit 0}]}))
        snap (cl/put-object c job {:sha (:sha job)})]
    (cl/complete c job verdict (get-in vobj [:body :cid]) (get-in receipt [:body :cid]) (get-in snap [:body :cid]))))

(use-fixtures :each (fn [t] (let [d (str (fs/create-temp-dir {:prefix "tatara-coord-"}))]
                              (binding [*root* d] (try (t) (finally (fs/delete-tree d)))))))

(deftest two-runners-reach-quorum-and-queue-status
  (let [s (start) ca (cl/client (base s) a clock) cb (cl/client (base s) b clock)]
    (try
      (let [run (submit s {:event event :pipeline pipeline})
            dup (submit s {:event (assoc event :delivery "x") :pipeline pipeline})
            ja (:body (cl/lease ca [:t2])) jb (:body (cl/lease cb [:t2]))]
        (is (= false (get-in run [:body :duplicate?])))
        (testing "a redelivered event is the same run"
          (is (= true (get-in dup [:body :duplicate?])))
          (is (= (get-in run [:body :run-id]) (get-in dup [:body :run-id]))))
        (testing "one DID never takes a second replica"
          (is (= :no-job (get-in (cl/lease ca [:t2]) [:body :error]))))
        (is (= 200 (:status (do-job ca ja "same bytes"))))
        (is (= :pending (:status (get-run s (:run-id ja)))))
        (let [done (do-job cb jb "same bytes")]
          (is (= :reached (get-in done [:body :run-status]))))
        (let [st @(:state s) acts (map :action (svc/pending-actions st))]
          (is (= #{"pending" "success"} (set (map :state acts))))
          (is (every? #(= "tatara/ci" (:context %)) acts))))
      (finally (srv/stop! s)))))

(deftest split-verdict-is-a-hard-error
  (let [s (start) ca (cl/client (base s) a clock) cb (cl/client (base s) b clock)]
    (try
      (submit s {:event event :pipeline pipeline})
      (let [ja (:body (cl/lease ca [:t2])) jb (:body (cl/lease cb [:t2]))]
        (do-job ca ja "bytes one")
        (let [r (do-job cb jb "bytes two")]
          (is (= :hard-error (get-in r [:body :run-status])))
          (is (= :split-threshold (get-in r [:body :quorum :reason]))))
        (is (some #(= "error" (get-in % [:action :state])) (svc/pending-actions @(:state s)))))
      (finally (srv/stop! s)))))

(deftest stale-expired-and-forged-requests-are-refused
  (let [s (start) ca (cl/client (base s) a clock) cb (cl/client (base s) b clock)
        cx (cl/client (base s) stranger clock)]
    (try
      (submit s {:event event :pipeline pipeline})
      (testing "a runner outside the allow list gets no lease"
        (is (= 403 (:status (cl/lease cx [:t2])))))
      (testing "a forged signature is rejected"
        (let [r (http/post (str (base s) "/v1/lease")
                           {:body (pr-str {:runner (:did a) :ts (clock) :tiers [:t2]})
                            :headers {"x-tatara-signature" (crypto/sign stranger "other")} :throw false})]
          (is (= 401 (:status r)))))
      (testing "a request outside the time window is rejected"
        (let [old (cl/client (base s) a (constantly (- (clock) 600000)))]
          (is (= 401 (:status (cl/lease old [:t2]))))))
      (let [ja (:body (cl/lease ca [:t2]))]
        (testing "a wrong token cannot upload or complete"
          (is (= 403 (:status (cl/put-blob ca (assoc ja :token "bad") (.getBytes "x" "UTF-8")))))
          (is (= 403 (:status (do-job ca (assoc ja :token (apply str (repeat 64 "0"))) "x")))))
        (testing "CAS objects must exist before completion"
          (is (= :cas-missing (get-in (do-job ca ja "x" :skip-receipt? true) [:body :error]))))
        (testing "an expired lease is requeued to another runner and the old holder is refused"
          (swap! now + 61000)
          (is (= :lease-expired (:error (:body (cl/heartbeat ca ja)))))
          (let [jb (:body (cl/lease cb [:t2]))]
            (is (= (:job-id ja) (:job-id jb)))
            (is (= :wrong-runner (get-in (do-job ca ja "x") [:body :error]))))))
      (finally (srv/stop! s)))))

(deftest state-survives-a-coordinator-restart
  (let [s1 (start)]
    (let [run-id (get-in (submit s1 {:event event :pipeline pipeline}) [:body :run-id])
          ja (:body (cl/lease (cl/client (base s1) a clock) [:t2]))]
      (do-job (cl/client (base s1) a clock) ja "same")
      (srv/stop! s1)
      (let [s2 (start) cb (cl/client (base s2) b clock)]
        (try
          (testing "the run, its first verdict and the used DID are restored"
            (is (= 1 (count (:entries (get-run s2 run-id)))))
            (is (= :no-job (get-in (cl/lease (cl/client (base s2) a clock) [:t2]) [:body :error]))))
          (let [jb (:body (cl/lease cb [:t2]))]
            (is (= :reached (get-in (do-job cb jb "same") [:body :run-status]))))
          (finally (srv/stop! s2)))))))

(deftest submission-checks
  (let [s (start)]
    (try
      (is (= :pipeline-cid-mismatch (:error (:body (submit s {:event (assoc event :pipeline-cid "cid:sha256:00") :pipeline pipeline})))))
      (is (= 401 (:status (http/post (str (base s) "/v1/runs") {:body "{}" :throw false}))))
      (testing "a runner without the run's tier gets no job"
        (submit s {:event event :pipeline pipeline})
        (is (= :no-job (get-in (cl/lease (cl/client (base s) a clock) [:t1]) [:body :error]))))
      (finally (srv/stop! s)))))

(def device-ctx
  "What a device uses: real Ed25519 over the canonical signed part."
  (assoc crypto/ctx :verify (fn [{:keys [runner signed signature]}]
                              (crypto/verify runner (c/canonical signed) signature))))

(defn- flip
  "Change the first character. The last base64 character of a signature has
  unused low bits, so changing it can decode to the same bytes."
  [^String s] (str (if (= \x (first s)) "y" "x") (subs s 1)))

(deftest bundle-reverifies-without-trusting-the-server
  (let [s (start) ca (cl/client (base s) a clock) cb (cl/client (base s) b clock)]
    (try
      (let [run-id (get-in (submit s {:event event :pipeline pipeline}) [:body :run-id])
            ja (:body (cl/lease ca [:t2])) jb (:body (cl/lease cb [:t2]))
            _ (do-job ca ja "same bytes") _ (do-job cb jb "same bytes")
            res (http/get (str (base s) "/v1/runs/" run-id "/bundle") {:throw false})
            bundle (edn/read-string (:body res))
            art (get-in bundle [:entries 0 :verdict :artifacts 0 :cid])]
        (is (= 200 (:status res)))
        (is (= 2 (count (:entries bundle))))
        (is (contains? (:objects bundle) art))
        (testing "the fetched bundle verifies with real signatures"
          (let [r (vf/verify-run device-ctx bundle)]
            (is (:ok? r) (pr-str (:failed r)))
            (is (= :reached (get-in r [:quorum :status])))))
        (testing "each tamper fails"
          (doseq [[what b] {:signature (update-in bundle [:entries 1 :signature] flip)
                            :result (assoc-in bundle [:entries 0 :verdict :result] :failed)
                            :request-sha (update-in bundle [:request :sha] flip)
                            :artifact-object (update-in bundle [:objects art :size] inc)
                            :claim (assoc-in bundle [:claimed :status] :pending)
                            :allowlist (update bundle :allow disj (:did b))}]
            (is (not (:ok? (vf/verify-run device-ctx b))) (str what))))
        (is (= 404 (:status (http/get (str (base s) "/v1/runs/cid:sha256:nope/bundle") {:throw false})))))
      (finally (srv/stop! s)))))

(deftest required-toolchain-is-routed-only-to-configured-runners
  (let [s (srv/start! {:root *root* :allow #{(:did a) (:did b)} :ttl-ms 60000
                       :runner-toolchains {(:did a) #{:cljs-jvm-v1}}}
                      {:clock clock :admin-token admin})
        ca (cl/client (base s) a clock)
        cb (cl/client (base s) b clock)
        required (assoc pipeline :toolchain :cljs-jvm-v1)
        jvm-event (assoc event :pipeline-cid (c/cid crypto/ctx required))]
    (try
      (is (= 200 (:status (submit s {:event jvm-event :pipeline required}))))
      (is (= :no-job (get-in (cl/lease cb [:t2]) [:body :error])))
      (is (= :cljs-jvm-v1 (get-in (cl/lease ca [:t2]) [:body :pipeline :toolchain])))
      (is (= 200 (:status (submit s {:event event :pipeline pipeline}))))
      (is (= pipeline (get-in (cl/lease cb [:t2]) [:body :pipeline])))
      (finally (srv/stop! s)))))
