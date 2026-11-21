(ns tatara.runner.runner-test
  "T5 (AWAI-3409, source): two runners with different DIDs execute a real
  pipeline from a source tarball served by an in-process coordinator. Steps run
  as host processes (macOS here, so network enforcement is :none and recorded
  as such); the Linux VM and unshare path is qualified on the fleet (AWAI-3411)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [tatara.core.canonical :as c]
            [tatara.coordinator.client :as cl]
            [tatara.coordinator.crypto :as crypto]
            [tatara.coordinator.http :as srv]
            [tatara.runner.core :as core]))

(def a (crypto/generate))
(def b (crypto/generate))
(def ^:dynamic *dir* nil)
(use-fixtures :each (fn [t] (let [d (str (fs/create-temp-dir {:prefix "tatara-runner-"}))]
                              (binding [*dir* d] (try (t) (finally (fs/delete-tree d)))))))

(defn tarball
  "A GitHub-style tarball (one top directory) holding pipeline-in-tree."
  [pipeline-in-tree]
  (let [top (fs/path *dir* "fixture" "repo-abc")]
    (fs/create-dirs (fs/path top ".tatara"))
    (spit (str (fs/path top ".tatara" "pipeline.edn")) (pr-str pipeline-in-tree))
    (spit (str (fs/path top "README")) "fixture")
    (p/shell "tar" "-czf" (str (fs/path *dir* "src.tgz")) "-C" (str (fs/path *dir* "fixture")) "repo-abc")
    (let [bytes (fs/read-all-bytes (fs/path *dir* "src.tgz"))]
      (fs/delete-tree (fs/path *dir* "fixture")) (fs/delete (fs/path *dir* "src.tgz"))
      bytes)))

(defn setup [submitted in-tree]
  (let [s (srv/start! {:root (str (fs/path *dir* "coord")) :allow #{(:did a) (:did b)} :ttl-ms 60000} {})
        snap (srv/put-blob! s (tarball in-tree))
        run (srv/submit! s {:event {:forge "github" :repo "network-awai/example" :ref "refs/heads/main"
                                    :sha "abc123" :pipeline-cid (c/cid crypto/ctx submitted)}
                            :pipeline submitted :snapshot snap})
        base (str "http://127.0.0.1:" (:port s))
        cfg (fn [n] {:work (str (fs/path *dir* n)) :tiers [:t2] :network-enforcement :none})]
    {:s s :run-id (:run-id run)
     :ra [(cfg "work-a") (cl/client base a)] :rb [(cfg "work-b") (cl/client base b)]}))

(defn run-view [{:keys [s run-id]}] (get-in @(:state s) [:runs run-id]))

(def ok-pipeline {:tier :t2 :network :none :timeout-seconds 60
                  :steps [["bb" "-e" "(spit \"out.txt\" (str \"hello \" (+ 1 2)))"]]
                  :artifacts ["out.txt"]})

(deftest two-runners-agree-on-a-real-pipeline
  (let [{:keys [s ra rb] :as env} (setup ok-pipeline ok-pipeline)]
    (try
      (is (:ok (apply core/run-once ra)))
      (is (= :pending (:status (run-view env))))
      (let [r (apply core/run-once rb)]
        (is (= :reached (:run-status r))))
      (is (= [:passed :passed] (mapv :result (:entries (run-view env)))))
      (testing "work directories are removed after submission"
        (is (empty? (fs/list-dir (:work (first ra))))))
      (finally (srv/stop! s)))))

(deftest a-failing-step-is-a-failed-verdict
  (let [p (assoc ok-pipeline :steps [["bb" "-e" "(System/exit 3)"] ["bb" "-e" "(spit \"out.txt\" \"never\")"]])
        {:keys [s ra rb] :as env} (setup p p)]
    (try
      (apply core/run-once ra)
      (is (= :reached (:run-status (apply core/run-once rb))))
      (is (= [:failed :failed] (mapv :result (:entries (run-view env)))))
      (finally (srv/stop! s)))))

(deftest the-deadline-stops-a-step
  (let [p (assoc ok-pipeline :timeout-seconds 1 :steps [["sleep" "30"]])
        {:keys [s ra] :as env} (setup p p)
        t0 (System/currentTimeMillis)]
    (try
      (apply core/run-once ra)
      (is (< (- (System/currentTimeMillis) t0) 15000))
      (is (= [:failed] (mapv :result (:entries (run-view env)))))
      (finally (srv/stop! s)))))

(deftest the-leased-pipeline-runs-even-when-the-checkout-differs
  (testing "a PR's own .tatara/pipeline.edn is ignored; the trusted, leased one runs (AWAI-3799)"
    (let [{:keys [s ra] :as env} (setup ok-pipeline (assoc ok-pipeline :network :fetch
                                                           :steps [["bb" "-e" "(System/exit 9)"]]))]
      (try
        (apply core/run-once ra)
        (is (= [:passed] (mapv :result (:entries (run-view env)))))
        (finally (srv/stop! s))))))

(deftest validation-rules
  (let [job (fn [p] {:pipeline p :pipeline-cid (c/cid crypto/ctx p)})]
    (is (:ok (core/validate (job ok-pipeline))))
    (testing ":fetch only with an egress proxy configured"
      (is (= :fetch-not-allowed (:error (core/validate (job (assoc ok-pipeline :network :fetch))))))
      (is (= :fetch-not-allowed (:error (core/validate (job (assoc ok-pipeline :network :fetch)) {}))))
      (is (:ok (core/validate (job (assoc ok-pipeline :network :fetch)) {:fetch {:proxy "http://egress:3128"}}))))
    (is (= :network-not-allowed (:error (core/validate (job (assoc ok-pipeline :network :any))))))
    (is (= :not-argv (:error (core/validate (job (assoc ok-pipeline :steps ["make test"]))))))
    (is (= :wrong-tier (:error (core/validate (job (assoc ok-pipeline :tier :t1))))))
    (is (= :bad-artifact-path (:error (core/validate (job (assoc ok-pipeline :artifacts ["../etc/passwd"]))))))
    (is (= :pipeline-cid-mismatch (:error (core/validate (assoc (job ok-pipeline) :pipeline-cid "cid:sha256:00")))))))

(deftest network-none-keeps-only-loopback
  (testing "unshare wraps the argv unchanged after a fixed loopback bring-up"
    (let [w (core/wrap-network ["bb" "ci"] :none :unshare)]
      (is (= ["unshare" "--net" "--map-root-user" "--"] (subvec w 0 4)))
      (is (= ["bb" "ci"] (subvec w (- (count w) 2))))))
  (testing ":fetch and :none without enforcement are not wrapped"
    (is (= ["bb" "ci"] (core/wrap-network ["bb" "ci"] :fetch :unshare)))
    (is (= ["bb" "ci"] (core/wrap-network ["bb" "ci"] :none :none)))))

(deftest steps-drop-to-the-step-user
  (let [u {:uid 20000 :gid 20000}]
    (testing "inside the pod user namespace: unshare without uid mapping, then drop privileges"
      (let [w (core/wrap-network ["bb" "ci"] :none :unshare u)]
        (is (= ["unshare" "--net" "--"] (subvec w 0 3)))
        (is (= ["setpriv" "--reuid=20000" "--regid=20000" "--clear-groups" "--inh-caps=-all" "--no-new-privs" "--" "bb" "ci"]
               (subvec w (- (count w) 9))))))
    (testing ":fetch steps also drop privileges and get only the proxy"
      (is (= "setpriv" (first (core/wrap-network ["npm" "ci"] :fetch :unshare u))))
      (is (= "http://egress:3128" (get (core/fetch-env {:fetch {:proxy "http://egress:3128"}} :fetch) "HTTPS_PROXY")))
      (is (= {} (core/fetch-env {:fetch {:proxy "http://egress:3128"}} :none))))))
