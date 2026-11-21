(ns tatara.coordinator.lease
  "Client for kagitaba-minter's `kagitaba-lease/v1` (AWAI-3459). The
  coordinator holds no long-lived GitHub token: it asks the minter for a
  scoped installation token (at most an hour), sealed to its own X25519 key,
  and refreshes it five minutes before expiry.

  Request: JSON body signed with the coordinator's Ed25519 identity (the same
  did:tatara:ed25519 form as runner DIDs) in `x-kagitaba-signature`.
  Response: key = HKDF-SHA256(salt \"kagitaba-lease/v1\", X25519(eph, me),
  info lease_id|did); AES-256-GCM with AAD lease_id|did|expires_at."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [tatara.coordinator.crypto :as crypto])
  (:import (java.security KeyFactory KeyPairGenerator SecureRandom)
           (java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec)
           (java.time Instant)
           (java.util Base64)
           (javax.crypto Cipher KeyAgreement Mac)
           (javax.crypto.spec GCMParameterSpec SecretKeySpec)))

(def format-id "kagitaba-lease/v1")
(def refresh-before-ms (* 5 60 1000))

;; HTTP/1.1 only: Java's HttpClient otherwise sends `Upgrade: h2c` on plain
;; http, which kagitaba-minter's server did not expect (AWAI-3459). Timeouts
;; keep a stuck minter from stalling the GitHub loop.
(def ^:private client
  (delay (http/client {:version :http1.1 :connect-timeout 10000})))
(def request-timeout-ms 30000)

(defn- b64 [^bytes b] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))
(defn- unhex [^String s] (let [b (byte-array (quot (count s) 2))]
                           (dotimes [i (count b)] (aset b i (unchecked-byte (Integer/parseInt (subs s (* 2 i) (+ 2 (* 2 i))) 16))))
                           b))
(def ^:private x25519-pkcs8-prefix (unhex "302e020100300506032b656e04220420"))
(def ^:private x25519-x509-prefix (unhex "302a300506032b656e032100"))
(def ^:private ed25519-pkcs8-prefix (unhex "302e020100300506032b657004220420"))
(defn- cat-bytes [& bs] (byte-array (mapcat seq bs)))

(defn x25519-private-from-raw [^bytes raw]
  (.generatePrivate (KeyFactory/getInstance "X25519") (PKCS8EncodedKeySpec. (cat-bytes x25519-pkcs8-prefix raw))))
(defn x25519-public-from-raw [^bytes raw]
  (.generatePublic (KeyFactory/getInstance "X25519") (X509EncodedKeySpec. (cat-bytes x25519-x509-prefix raw))))
(defn- raw-public [pub] (let [enc (.getEncoded pub)] (java.util.Arrays/copyOfRange enc (- (count enc) 32) (count enc))))

(defn generate-identity
  "Fresh coordinator identity: Ed25519 (DID) and X25519. Write it with 600 perms."
  []
  (let [ed (crypto/generate)
        kp (.generateKeyPair (KeyPairGenerator/getInstance "X25519"))]
    (assoc ed :x25519-private (b64 (.getEncoded (.getPrivate kp)))
              :x25519-public (apply str (map #(format "%02x" (bit-and % 0xff)) (raw-public (.getPublic kp)))))))

(defn write-identity! [path]
  (when (.exists (io/file path)) (throw (ex-info "identity file exists; refusing to overwrite" {:path path})))
  (let [id (generate-identity) f (io/file path)]
    (spit f (pr-str id))
    (.setReadable f false false) (.setReadable f true true)
    (println (:did id))
    (println "x25519" (:x25519-public id))))

(defn- hmac [^bytes key ^bytes data]
  (let [m (Mac/getInstance "HmacSHA256")] (.init m (SecretKeySpec. key "HmacSHA256")) (.doFinal m data)))

(defn hkdf-32 [^bytes salt ^bytes ikm ^bytes info]
  (let [prk (hmac salt ikm)] (hmac prk (cat-bytes info (byte-array [(byte 1)])))))

(defn open-lease
  "Open a minter response with the X25519 private key (PKCS8, base64url)."
  [x25519-private-b64 did {:keys [lease_id expires_at sealed]}]
  (let [priv (.generatePrivate (KeyFactory/getInstance "X25519")
                               (PKCS8EncodedKeySpec. (.decode (Base64/getUrlDecoder) ^String x25519-private-b64)))
        ka (doto (KeyAgreement/getInstance "X25519") (.init priv) (.doPhase (x25519-public-from-raw (unhex (:eph sealed))) true))
        k (hkdf-32 (.getBytes ^String format-id "UTF-8") (.generateSecret ka) (.getBytes (str lease_id "|" did) "UTF-8"))
        c (doto (Cipher/getInstance "AES/GCM/NoPadding")
            (.init Cipher/DECRYPT_MODE (SecretKeySpec. k "AES") (GCMParameterSpec. 128 (unhex (:nonce sealed))))
            (.updateAAD (.getBytes (str lease_id "|" did "|" expires_at) "UTF-8")))]
    (String. (.doFinal c (unhex (:ct sealed))) "UTF-8")))

(defn- nonce [] (let [b (byte-array 16)] (.nextBytes (SecureRandom.) b) (apply str (map #(format "%02x" (bit-and % 0xff)) b))))

(defn request-lease
  "Ask the minter for a token. Returns {:token :expires-ms :lease-id}.
  :timeout-ms (default 30 s) bounds the request; a minter with a large vault
  on a loaded host took 204 s per lease on 2026-11-21 (AWAI-3799)."
  [{:keys [url identity owner repositories permissions ttl-s timeout-ms]} now-ms]
  (let [body (json/generate-string {:format format-id :requester (:did identity) :x25519 (:x25519-public identity)
                                    :issuer "github" :owner owner :repositories repositories
                                    :permissions permissions :ttl_s (or ttl-s 3600) :nonce (nonce) :ts now-ms})
        res (http/post (str url "/v1/lease") {:headers {"content-type" "application/json"
                                                         "x-kagitaba-signature" (crypto/sign identity body)}
                                               :body body :throw false
                                               :client @client :timeout (or timeout-ms request-timeout-ms)})
        out (try (json/parse-string (:body res) true) (catch Exception _ nil))]
    (when-not (= 200 (:status res))
      (throw (ex-info "lease refused" {:status (:status res) :error (:error out)})))
    {:token (open-lease (:x25519-private identity) (:did identity) out)
     :expires-ms (.toEpochMilli (Instant/parse (:expires_at out)))
     :lease-id (:lease_id out)}))

(defn token-provider
  "A zero-argument fn returning a valid token, leasing a new one when the
  cached one is within five minutes of expiry. clock is injectable for tests."
  [cfg & [{:keys [clock] :or {clock #(System/currentTimeMillis)}}]]
  (let [cache (atom nil)]
    (fn []
      (locking cache
        (let [now (clock) c @cache]
          (if (and c (< now (- (:expires-ms c) refresh-before-ms)))
            (:token c)
            (:token (reset! cache (request-lease cfg now)))))))))

(defn read-identity [path] (edn/read-string (slurp path)))
