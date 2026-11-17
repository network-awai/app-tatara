(ns tatara.verify-test
  "P1 acceptance (source): a fixed fixture run verifies; every one-byte tamper
  fails. :verify here is a stand-in signature (hash of signer + verdict CID),
  not a real signature scheme; the Identity port supplies the real one."
  (:require [clojure.test :refer [deftest is testing]]
            [tatara.core-test :refer [ctx sha256-hex event]]
            [tatara.core.run :as run]
            [tatara.core.verdict :as v]
            [tatara.core.verify :as vf]))

(defn sign [runner vcid] (sha256-hex (str runner "|" vcid)))
(defn verify-port [{:keys [runner signed signature]}]
  (= signature (sign runner (:verdict-cid signed))))

(def vctx (assoc ctx :verify verify-port))

(def artifact "built-bytes-v1")
(def artifact-cid (tatara.core.canonical/cid ctx artifact))

(defn entry [runner]
  (let [verdict {:source "abc" :pipeline-cid "cid:p" :result :passed
                 :artifacts [{:name "out" :cid artifact-cid}]}
        vc (v/verdict-cid ctx verdict)]
    {:runner runner :verdict verdict
     :signed {:rid "rid1" :ref "refs/results/r1" :verdict-cid vc}
     :signature (sign runner vc)}))

(def bundle
  (let [req (:ok (run/normalize ctx event))
        es [(entry "did:a") (entry "did:b")]]
    {:request req :threshold 2 :allow #{"did:a" "did:b"} :entries es
     :objects {artifact-cid artifact}
     :claimed {:status :reached
               :verdict-cid (get-in (first es) [:signed :verdict-cid])}}))

(defn flip-char [^String s]
  (str (subs s 0 (dec (count s))) (if (= \x (last s)) "y" "x")))

(deftest fixture-verifies
  (let [r (vf/verify-run vctx bundle)]
    (is (:ok? r))
    (is (empty? (:failed r)))
    (is (= :reached (get-in r [:quorum :status])))))

(deftest one-byte-tampers-fail
  (testing "artifact bytes"
    (is (not (:ok? (vf/verify-run vctx (update-in bundle [:objects artifact-cid] flip-char))))))
  (testing "request sha"
    (is (not (:ok? (vf/verify-run vctx (update-in bundle [:request :sha] flip-char))))))
  (testing "verdict result"
    (is (not (:ok? (vf/verify-run vctx (assoc-in bundle [:entries 0 :verdict :result] :failed))))))
  (testing "signature"
    (is (not (:ok? (vf/verify-run vctx (update-in bundle [:entries 1 :signature] flip-char))))))
  (testing "claimed verdict cid"
    (is (not (:ok? (vf/verify-run vctx (update-in bundle [:claimed :verdict-cid] flip-char)))))))

(deftest server-cannot-overclaim
  (testing "one DID twice does not reach quorum, claim of :reached is caught"
    (let [b (-> bundle (assoc :entries [(entry "did:a") (entry "did:a")]))]
      (is (not (:ok? (vf/verify-run vctx b))))
      (is (= :pending (get-in (vf/verify-run vctx b) [:quorum :status])))))
  (testing "runner off the allowlist"
    (is (not (:ok? (vf/verify-run vctx (assoc bundle :allow #{"did:a"}))))))
  (testing "missing artifact object"
    (is (not (:ok? (vf/verify-run vctx (assoc bundle :objects {})))))))
