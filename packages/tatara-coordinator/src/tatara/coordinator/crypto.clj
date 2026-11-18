(ns tatara.coordinator.crypto
  "SHA-256 and Ed25519 runner identities. A runner DID here is
  `did:tatara:ed25519:<base64url SPKI>`; it is a local identifier, not the
  did:key multicodec form and not a kotoba-rad identity."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security KeyFactory KeyPairGenerator MessageDigest Signature)
           (java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec)
           (java.util Base64)))

(defn hex [^bytes b] (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(defn sha256-bytes [^bytes b]
  (hex (.digest (MessageDigest/getInstance "SHA-256") b)))

(defn sha256-hex [^String s] (sha256-bytes (.getBytes s "UTF-8")))

(def ctx {:hash sha256-hex})

(defn- b64 [^bytes b] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))
(defn- unb64 [^String s] (.decode (Base64/getUrlDecoder) s))

(def did-prefix "did:tatara:ed25519:")

(defn generate []
  (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))]
    {:did (str did-prefix (b64 (.getEncoded (.getPublic kp))))
     :private (b64 (.getEncoded (.getPrivate kp)))}))

(defn write-identity! [path]
  (when (.exists (io/file path)) (throw (ex-info "identity file exists; refusing to overwrite" {:path path})))
  (let [id (generate) f (io/file path)]
    (spit f (pr-str id))
    (.setReadable f false false) (.setReadable f true true)
    (.setWritable f false false) (.setWritable f true true)
    (println (:did id))))

(defn read-identity [path] (edn/read-string (slurp path)))

(defn sign [{:keys [private]} ^String msg]
  (let [k (.generatePrivate (KeyFactory/getInstance "Ed25519") (PKCS8EncodedKeySpec. (unb64 private)))
        s (doto (Signature/getInstance "Ed25519") (.initSign k) (.update (.getBytes msg "UTF-8")))]
    (b64 (.sign s))))

(defn verify [^String did ^String msg ^String sig]
  (try
    (when (and (string? did) (str/starts-with? did did-prefix) (string? sig))
      (let [k (.generatePublic (KeyFactory/getInstance "Ed25519")
                               (X509EncodedKeySpec. (unb64 (subs did (count did-prefix)))))
            s (doto (Signature/getInstance "Ed25519") (.initVerify k) (.update (.getBytes msg "UTF-8")))]
        (.verify s (unb64 sig))))
    (catch Exception _ false)))
