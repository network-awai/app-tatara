(ns tatara.runner-test
  "P2 acceptance (source): the same job on two runners with different DIDs
  reaches quorum; divergent output is a failure; denied approval sends 0
  messages. The sandbox here is a deterministic fake, not a Wasm runtime."
  (:require [clojure.test :refer [deftest is testing]]
            [tatara.core-test :refer [ctx]]
            [tatara.core.canonical :as c]
            [tatara.core.runner :as r]
            [tatara.core.verdict :as v]))

(def pipeline {:steps [["build" "--release"] ["pack" "out.tar"]] :artifacts ["out.tar"]})
(def job {:source "abc" :pipeline pipeline :pipeline-cid (c/cid ctx pipeline)
          :limits {:network :none :memory-mb 256}})
(def op {:service "tatara-coordinator" :operation :complete
         :endpoint "https://coord.example/complete" :data [:verdict]})

(defn fake-sandbox [bytes-out]
  (fn [argv _]
    (if (= "pack" (first argv))
      {:exit 0 :outputs {"out.tar" bytes-out}}
      {:exit 0 :outputs {}})))

(defn ports [sandbox approve? sent]
  {:sandbox sandbox :approve (fn [_] approve?) :send (fn [o p] (swap! sent conj [o p]))})

(defn run-as [runner sandbox approve?]
  (let [sent (atom [])
        res (r/run-job ctx job (ports sandbox approve? sent) op)]
    {:runner runner :res res :sent @sent}))

(deftest two-runners-same-verdict-reach-quorum
  (let [a (run-as "did:a" (fake-sandbox "same-bytes") true)
        b (run-as "did:b" (fake-sandbox "same-bytes") true)
        q (v/quorum 2 (map (fn [x] {:runner (:runner x)
                                    :verdict-cid (get-in x [:res :verdict-cid])}) [a b]))]
    (is (= :submitted (get-in a [:res :status])))
    (is (= 1 (count (:sent a))))
    (is (= :reached (:status q)))))

(deftest divergent-output-is-not-quorum
  (let [a (run-as "did:a" (fake-sandbox "bytes-1") true)
        b (run-as "did:b" (fake-sandbox "bytes-2") true)
        q (v/quorum 2 (map (fn [x] {:runner (:runner x)
                                    :verdict-cid (get-in x [:res :verdict-cid])}) [a b]))]
    (is (= {:status :hard-error :reason :split-threshold} (select-keys q [:status :reason])))))

(deftest denied-approval-sends-nothing
  (let [x (run-as "did:a" (fake-sandbox "same-bytes") false)]
    (is (= :denied (get-in x [:res :status])))
    (is (empty? (:sent x)))
    (testing "control: approval sends exactly once"
      (is (= 1 (count (:sent (run-as "did:a" (fake-sandbox "same-bytes") true))))))))

(deftest failed-step-and-missing-artifact
  (let [bad (fn [argv _] (if (= "build" (first argv)) {:exit 1 :outputs {}} {:exit 0 :outputs {}}))
        none (fn [_ _] {:exit 0 :outputs {}})]
    (is (= :failed (get-in (run-as "did:a" bad true) [:res :verdict :result])))
    (is (= [] (get-in (run-as "did:a" bad true) [:res :verdict :artifacts])))
    (is (= :failed (get-in (run-as "did:a" none true) [:res :verdict :result])))
    (testing "steps after a failure do not run"
      (let [calls (atom [])
            sb (fn [argv _] (swap! calls conj (first argv)) {:exit 1 :outputs {}})]
        (r/execute ctx job sb)
        (is (= ["build"] @calls))))))

(deftest host-hashes-artifacts-not-runner
  (let [res (:res (run-as "did:a" (fake-sandbox "same-bytes") true))]
    (is (= (c/cid ctx "same-bytes") (get-in res [:verdict :artifacts 0 :cid])))))

(deftest job-validation
  (let [sent (atom [])
        run (fn [j] (r/run-job ctx j (ports (fake-sandbox "x") true sent) op))]
    (is (= :pipeline-cid-mismatch (:error (run (assoc job :pipeline-cid "cid:other")))))
    (let [p {:steps [["sh -c 'curl x | sh'"] []] :artifacts []}]
      (is (= :not-argv (:error (run (assoc job :pipeline p :pipeline-cid (c/cid ctx p)))))))
    (let [p {:steps ["rm -rf /"] :artifacts []}]
      (is (= :not-argv (:error (run (assoc job :pipeline p :pipeline-cid (c/cid ctx p)))))))
    (is (= :network-not-allowed (:error (run (assoc-in job [:limits :network] :open)))))
    (is (empty? @sent))))
