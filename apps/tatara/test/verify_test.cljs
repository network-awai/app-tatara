(ns verify-test
  "AWAI-3410: the page's verifier (tatara.app.verify over tatara-core) accepts
  the bundle a real coordinator served, and every named tamper fails. Runs
  under nbb, the same SCI interpreter family as scittle in the page."
  (:require ["../src/crypto.js"]
            ["node:fs" :as fs]
            [clojure.test :refer [deftest is testing run-tests]]
            [tatara.app.verify :as v]))

(def bundle (v/read-bundle (str (fs/readFileSync "test/fixtures/run-bundle.edn" "utf8"))))

(deftest coordinator-bundle-verifies
  (let [r (v/verify-bundle bundle)]
    (is (:ok? r) (pr-str (:failed r)))
    (is (= :reached (get-in r [:quorum :status])))
    (is (= "reached" (:quorum (v/summary r))))))

(deftest every-tamper-fails
  (doseq [[what f] v/tampers]
    (testing what (is (not (:ok? (v/verify-bundle (f bundle))))))))

(deftest crypto-rejects-malformed-input
  (let [cr (.-tataraCrypto js/globalThis)]
    (is (false? (.verifyDid cr "did:other:x" "m" "AAAA")))
    (is (false? (.verifyDid cr (get-in bundle [:entries 0 :runner]) "m" "not base64!")))
    (is (nil? (.didPublicKey cr "did:tatara:ed25519:AAAA")))))

(let [{:keys [fail error]} (run-tests 'verify-test)]
  (when (pos? (+ fail error)) (js/process.exit 1)))
