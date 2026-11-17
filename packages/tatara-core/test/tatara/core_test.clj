(ns tatara.core-test
  "P0 acceptance: one or more cases per ADR-0001 failure mode, each with a
  negative control next to its positive case. Run with `bb test`."
  (:require [clojure.test :refer [deftest is testing]]
            [tatara.core.canonical :as c]
            [tatara.core.run :as run]
            [tatara.core.lease :as lease]
            [tatara.core.verdict :as v]
            [tatara.core.outbox :as ob]
            [tatara.core.cd :as cd])
  (:import (java.security MessageDigest)))

(defn sha256-hex [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(def ctx {:hash sha256-hex})

(def event {:forge "github" :repo "network-awai/x" :ref "refs/heads/main"
            :sha "abc" :pipeline-cid "cid:p"})

;; canonical form ------------------------------------------------------------

(deftest canonical-is-order-independent
  (is (= (c/cid ctx {:a 1 :b [1 2]}) (c/cid ctx {:b [1 2] :a 1})))
  (is (not= (c/cid ctx {:a 1}) (c/cid ctx {:a 2})))
  (is (thrown? Exception (c/canonical {:a 1.5}))))

;; 1. duplicate forge events ---------------------------------------------------

(deftest duplicate-events-collapse
  (let [r1 (:ok (run/normalize ctx (assoc event :delivery-id "d1")))
        r2 (:ok (run/normalize ctx (assoc event :delivery-id "d2")))
        a1 (run/admit {} r1)
        a2 (run/admit (:state a1) r2)]
    (is (= (:run/id r1) (:run/id r2)))
    (is (false? (:duplicate? a1)))
    (is (true? (:duplicate? a2)))
    (is (= 1 (count (:runs (:state a2))))))
  (testing "control: a different sha is a different run"
    (is (not= (:run/id (:ok (run/normalize ctx event)))
              (:run/id (:ok (run/normalize ctx (assoc event :sha "def")))))))
  (testing "invalid event is rejected"
    (is (= :invalid-event (:error (run/normalize ctx (dissoc event :sha)))))))

;; 2. runner disappears / stale lease / ownership ------------------------------

(defn leased-state []
  (let [jobs (lease/expand-replicas ctx "r1" 2)
        s0 {:jobs jobs}
        c1 (lease/claim ctx s0 {:run-id "r1" :runner "did:a" :token "t-a" :now 0 :ttl 10})]
    [(:state c1) (:job-id c1)]))

(deftest lease-rules
  (let [[s jid] (leased-state)
        pres {:job-id jid :runner "did:a" :token "t-a" :now 5}]
    (is (:ok (lease/check ctx s pres)))
    (is (= :stale-token (:error (lease/check ctx s (assoc pres :token "old")))))
    (is (= :wrong-runner (:error (lease/check ctx s (assoc pres :runner "did:b")))))
    (is (= :lease-expired (:error (lease/check ctx s (assoc pres :now 10)))))
    (testing "expired lease is requeued to another runner, stale token then fails"
      (let [c2 (lease/claim ctx s {:run-id "r1" :runner "did:b" :token "t-b" :now 20 :ttl 10})]
        (is (:job-id c2))
        (is (= :wrong-runner (:error (lease/check ctx (:state c2) (assoc pres :now 21)))))))
    (testing "the token itself is not stored"
      (is (not-any? #(= "t-a" %) (vals (get-in s [:jobs jid])))))))

;; 3. one runner cannot satisfy a multi-runner threshold ----------------------

(deftest one-runner-one-replica
  (let [[s _] (leased-state)]
    (is (= :runner-already-used
           (:error (lease/claim ctx s {:run-id "r1" :runner "did:a" :token "t2" :now 1 :ttl 10}))))
    (testing "even after its lease expired"
      (is (= :runner-already-used
             (:error (lease/claim ctx s {:run-id "r1" :runner "did:a" :token "t2" :now 99 :ttl 10})))))
    (testing "control: another DID can claim"
      (is (:job-id (lease/claim ctx s {:run-id "r1" :runner "did:b" :token "t-b" :now 1 :ttl 10}))))))

(deftest quorum-counts-distinct-dids
  (is (= :pending (:status (v/quorum 2 [{:runner "did:a" :verdict-cid "x"}
                                        {:runner "did:a" :verdict-cid "x"}]))))
  (is (= :reached (:status (v/quorum 2 [{:runner "did:a" :verdict-cid "x"}
                                        {:runner "did:b" :verdict-cid "x"}]))))
  (is (= :runner-equivocation
         (:reason (v/quorum 2 [{:runner "did:a" :verdict-cid "x"}
                               {:runner "did:a" :verdict-cid "y"}])))))

;; 4. honest runners disagree --------------------------------------------------

(deftest split-threshold-is-hard-error
  (let [a {:runner "did:a" :verdict-cid "x"} b {:runner "did:b" :verdict-cid "y"}]
    (is (= {:status :hard-error :reason :split-threshold :verdicts ["x" "y"]}
           (v/quorum 2 [a b])))
    (testing "arrival order does not change the result"
      (is (= (v/quorum 2 [a b]) (v/quorum 2 [b a]))))
    (testing "a third runner does not resolve the split by majority"
      (is (= :hard-error (:status (v/quorum 2 [a b {:runner "did:c" :verdict-cid "x"}])))))
    (testing "control: below threshold the run just waits"
      (is (= :pending (:status (v/quorum 3 [a])))))))

;; 5. runner claims artifacts it did not upload / completion admission --------

(def verdict {:source "abc" :pipeline-cid "cid:p" :result :passed
              :artifacts [{:name "out" :cid "cid:out"}]
              :timing-ms 5}) ; excluded from the verdict CID

(defn submission [s jid]
  (let [vc (v/verdict-cid ctx verdict)]
    {:job-id jid :runner "did:a" :token "t-a" :now 5 :verdict verdict
     :receipt-cid "cid:rcpt" :snapshot-cid "cid:snap"
     :signed {:rid "rid1" :ref "refs/results/r1" :verdict-cid vc}}))

(defn actx [cas]
  {:hash sha256-hex :allow #{"did:a" "did:b"} :verify (constantly true)
   :cas-has? (set cas)})

(def expected {:rid "rid1" :ref "refs/results/r1"})
(def all-cas ["cid:rcpt" "cid:snap" "cid:out"])

(deftest completion-admission
  (let [[s jid] (leased-state)
        sub (submission s jid)
        vc (v/verdict-cid ctx verdict)
        vcas (conj all-cas vc)]
    (testing "control: everything present is admitted"
      (is (= {:ok {:runner "did:a" :verdict-cid vc}}
             (v/admit-completion (actx vcas) s expected sub))))
    (testing "timing is not part of the verdict CID"
      (is (= vc (v/verdict-cid ctx (assoc verdict :timing-ms 999)))))
    (testing "missing artifact in CAS refuses completion"
      (let [r (v/admit-completion (actx (remove #{"cid:out"} vcas)) s expected sub)]
        (is (= :cas-missing (:error r)))
        (is (= ["cid:out"] (:missing r)))))
    (testing "missing verdict object in CAS refuses completion"
      (is (= :cas-missing (:error (v/admit-completion (actx all-cas) s expected sub)))))
    (testing "tampered verdict does not match the signed CID"
      (is (= :verdict-cid-mismatch
             (:error (v/admit-completion (actx vcas) s expected
                                         (assoc-in sub [:verdict :result] :failed))))))
    (testing "wrong rid / ref"
      (is (= :wrong-rid (:error (v/admit-completion (actx vcas) s (assoc expected :rid "x") sub))))
      (is (= :wrong-ref (:error (v/admit-completion (actx vcas) s (assoc expected :ref "x") sub)))))
    (testing "runner not on the allowlist, bad signature, stale token"
      (is (= :runner-not-allowed (:error (v/admit-completion (assoc (actx vcas) :allow #{}) s expected sub))))
      (is (= :bad-signature (:error (v/admit-completion (assoc (actx vcas) :verify (constantly false)) s expected sub))))
      (is (= :stale-token (:error (v/admit-completion (actx vcas) s expected (assoc sub :token "old"))))))))

;; 6. external API fails after the verdict is durable -------------------------

(deftest outbox-retry-and-no-repeat
  (let [act {:kind :github-status :sha "abc" :state :success}
        {o1 :outbox k :key} (ob/enqueue ctx {} act)]
    (is (= [{:key k :action act}] (ob/pending o1)))
    (testing "restart: still pending until delivered"
      (is (= 1 (count (ob/pending o1)))))
    (let [o2 (:outbox (ob/mark-delivered o1 k))]
      (is (empty? (ob/pending o2)))
      (testing "re-enqueue after delivery does not repeat the action"
        (is (empty? (ob/pending (:outbox (ob/enqueue ctx o2 act)))))))
    (testing "marking an unknown action is refused"
      (is (= :unknown-action (:error (ob/mark-delivered o1 "nope")))))))

;; 7. accidental deployment of a PR / feature ref -----------------------------

(deftest cd-ref-and-bundle-gate
  (let [policy {:repos {"network-awai/x" #{"refs/heads/main"}}}
        pass {:result :passed :artifacts [{:name "b" :type :release-bundle :cid "cid:b"}
                                          {:name "o" :type :log :cid "cid:o"}]}]
    (is (:ok (cd/eligible policy {:repo "network-awai/x" :ref "refs/heads/main" :verdict pass})))
    (is (= :ref-not-allowed (:error (cd/eligible policy {:repo "network-awai/x" :ref "refs/pull/5/head" :verdict pass}))))
    (is (= :ref-not-allowed (:error (cd/eligible policy {:repo "network-awai/x" :ref "refs/heads/main-2" :verdict pass}))))
    (is (= :ref-not-allowed (:error (cd/eligible policy {:repo "other/y" :ref "refs/heads/main" :verdict pass}))))
    (testing "ordinary artifacts get no deploy authority"
      (is (= :bundle-count (:error (cd/release-bundle {:result :passed :artifacts [{:type :log :cid "c"}]}))))
      (is (= :bundle-count (:error (cd/release-bundle {:result :passed :artifacts [{:type :release-bundle} {:type :release-bundle}]}))))
      (is (= :not-passed (:error (cd/release-bundle (assoc pass :result :failed))))))))

;; 8. capability expiry, canary failure, partial promotion, rollback ----------

(deftest cd-capability-and-rollback
  (let [cap {:issuer "did:iss" :env "prod" :not-before 10 :not-after 20 :nonce "n1"}
        node {:issuers #{"did:iss"} :env "prod" :seen-nonces #{}}]
    (is (:ok (cd/capability-valid? node cap 15)))
    (is (= :expired (:error (cd/capability-valid? node cap 20))))
    (is (= :not-yet-valid (:error (cd/capability-valid? node cap 9))))
    (is (= :issuer-not-allowed (:error (cd/capability-valid? (assoc node :issuers #{"did:other"}) cap 15))))
    (is (= :wrong-env (:error (cd/capability-valid? (assoc node :env "stg") cap 15))))
    (is (= :replayed-nonce (:error (cd/capability-valid? (assoc node :seen-nonces #{"n1"}) cap 15)))))
  (testing "canary first, then batches; rollback is the reverse of touching order"
    (is (= [["n1"] ["n2" "n3"] ["n4"]] (mapv vec (cd/rollout-plan ["n1" "n2" "n3" "n4"] 2))))
    (is (nil? (cd/rollout-plan [] 2)))
    (is (= ["n3" "n2" "n1"] (cd/rollback-order ["n1" "n2" "n3"])))))

;; 9. power loss while publishing state: NOT covered here. Atomic write and
;; fsync are I/O; they belong to tatara-coordinator and are recorded as open.
