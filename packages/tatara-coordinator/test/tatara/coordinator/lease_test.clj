(ns tatara.coordinator.lease-test
  "AWAI-3459: interoperability with kagitaba-minter (fixture produced by its
  `lease_fixture` example; test keys only) and the caching token provider."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as hs]
            [tatara.coordinator.crypto :as crypto]
            [tatara.coordinator.lease :as lease])
  (:import (java.time Instant)
           (java.util Base64)))

(def fx (json/parse-string (slurp "test/fixtures/kagitaba-lease-fixture.json") true))

(defn- unhex [s] (let [b (byte-array (quot (count s) 2))]
                   (dotimes [i (count b)] (aset b i (unchecked-byte (Integer/parseInt (subs s (* 2 i) (+ 2 (* 2 i))) 16)))) b))
(defn- b64 [^bytes b] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))
(defn- pkcs8 [prefix raw] (b64 (byte-array (concat (seq (unhex prefix)) (seq (unhex raw))))))

(def identity-fx
  {:did (:did fx)
   :private (pkcs8 "302e020100300506032b657004220420" (:ed25519_seed fx))
   :x25519-private (pkcs8 "302e020100300506032b656e04220420" (:x25519_secret fx))
   :x25519-public (:x25519_public fx)})

(deftest same-signatures-and-sealing-as-the-rust-minter
  (testing "bb signs the request body exactly like the Rust fixture (Ed25519 is deterministic)"
    (is (= (:request_signature fx) (crypto/sign identity-fx (:request_body fx))))
    (is (crypto/verify (:did fx) (:request_body fx) (:request_signature fx))))
  (testing "bb opens a lease sealed by the Rust minter"
    (is (= (:token fx) (lease/open-lease (:x25519-private identity-fx) (:did fx) (:response fx)))))
  (testing "the expiry is authenticated"
    (is (thrown? Exception (lease/open-lease (:x25519-private identity-fx) (:did fx)
                                             (assoc (:response fx) :expires_at "2099-01-01T00:00:00Z"))))))

(defn- fake-minter [status calls]
  (let [s (hs/run-server
           (fn [{:keys [body headers]}]
             (let [text (slurp body)]
               (swap! calls conj (json/parse-string text true))
               (if (crypto/verify (:did fx) text (get headers "x-kagitaba-signature"))
                 {:status status :body (json/generate-string (if (= 200 status) (:response fx) {:error "repository not granted"}))}
                 {:status 401 :body "{\"error\":\"bad signature\"}"})))
           {:port 0 :legacy-return-value? false})]
    {:stop #(hs/server-stop! s) :url (str "http://127.0.0.1:" (hs/server-port s))}))

(deftest provider-caches-and-refreshes
  (let [calls (atom []) m (fake-minter 200 calls)
        expires (.toEpochMilli (Instant/parse (get-in fx [:response :expires_at])))
        now (atom (- expires (* 50 60 1000)))
        cfg {:url (:url m) :identity identity-fx :owner "network-awai" :repositories ["app-tatara"]
             :permissions {:contents "read" :statuses "write"} :ttl-s 3600}
        token (lease/token-provider cfg {:clock #(deref now)})]
    (try
      (is (= "ghs_fixture" (token)))
      (is (= "ghs_fixture" (token)))
      (is (= 1 (count @calls)) "a valid lease is reused")
      (is (= ["app-tatara"] (:repositories (first @calls))))
      (is (= (:x25519_public fx) (:x25519 (first @calls))) "the lease is sealed to our own key")
      (reset! now (- expires (* 4 60 1000)))
      (token)
      (is (= 2 (count @calls)) "within five minutes of expiry a new lease is requested")
      (is (not= (:nonce (first @calls)) (:nonce (second @calls))))
      (finally ((:stop m))))))

(deftest refused-lease-is-an-error
  (let [calls (atom []) m (fake-minter 403 calls)]
    (try
      (is (thrown-with-msg? Exception #"lease refused"
                            ((lease/token-provider {:url (:url m) :identity identity-fx :owner "network-awai"
                                                    :repositories ["awai-os"] :permissions {:contents "read"}}))))
      (finally ((:stop m))))))

(deftest lease-request-is-plain-http1
  (testing "no h2c upgrade header reaches the minter (AWAI-3459 live finding)"
    (let [seen (atom nil)
          s (hs/run-server (fn [{:keys [headers]}] (reset! seen headers)
                             {:status 403 :body "{\"error\":\"repository not granted\"}"})
                           {:port 0 :legacy-return-value? false})]
      (try
        (is (thrown? clojure.lang.ExceptionInfo
                     (lease/request-lease {:url (str "http://127.0.0.1:" (hs/server-port s)) :identity identity-fx
                                           :owner "network-awai" :repositories ["app-tatara"]
                                           :permissions {:contents "read"}} (System/currentTimeMillis))))
        (is (some? @seen))
        (is (nil? (get @seen "upgrade")))
        (is (nil? (get @seen "http2-settings")))
        (finally (hs/server-stop! s))))))

(deftest a-down-or-locked-minter-is-reported-not-silent
  (testing "connection refused is classified as :minter-unreachable and recorded (AWAI-3820)"
    (let [port (with-open [ss (java.net.ServerSocket. 0)] (.getLocalPort ss))
          url (str "http://127.0.0.1:" port)
          p (lease/token-provider {:url url :identity (lease/generate-identity) :owner "o"
                                   :repositories ["r"] :permissions {:contents "read"}})]
      (is (thrown? Exception (p)))
      (is (= :minter-unreachable (get-in @lease/status [url :state])))
      (is (re-find #"unlock-keychain" (get-in @lease/status [url :detail]))))))
