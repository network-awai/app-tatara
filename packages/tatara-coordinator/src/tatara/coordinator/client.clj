(ns tatara.coordinator.client
  "Runner-side calls to the coordinator. Every request body is signed with the
  runner identity; job operations also present the lease token."
  (:require [babashka.http-client :as http]
            [clojure.edn :as edn]
            [tatara.core.canonical :as c]
            [tatara.coordinator.crypto :as crypto]))

(defn- post [{:keys [base identity clock]} path body]
  (let [text (pr-str (assoc body :runner (:did identity) :ts (clock)))
        res (http/post (str base path)
                       {:headers {"content-type" "application/edn"
                                  "x-tatara-signature" (crypto/sign identity text)}
                        :body text :throw false})]
    {:status (:status res) :body (when (seq (:body res)) (edn/read-string (:body res)))}))

(defn client [base identity & [clock]]
  {:base base :identity identity :clock (or clock #(System/currentTimeMillis))})

(defn lease
  "toolchain-only?: lease only runs that need one of this runner's toolchains (AWAI-3834)."
  [cl tiers & [toolchain-only?]]
  (post cl "/v1/lease" (cond-> {:tiers (vec tiers)} toolchain-only? (assoc :toolchain-only? true))))

(defn heartbeat [cl job] (post cl "/v1/heartbeat" (select-keys job [:job-id :token])))

(defn put-blob [{:keys [base identity]} job ^bytes data]
  (let [sha (crypto/sha256-bytes data)
        res (http/put (str base "/v1/blob/" sha)
                      {:headers {"x-tatara-job" (:job-id job) "x-tatara-runner" (:did identity)
                                 "x-tatara-token" (:token job)}
                       :body data :throw false})]
    {:status (:status res) :sha sha :body (when (seq (:body res)) (edn/read-string (:body res)))}))

(defn put-object [cl job value]
  (post cl "/v1/object" (assoc (select-keys job [:job-id :token]) :value value)))

(defn complete
  "Sign {:job-id :rid :ref :verdict-cid} and submit the verdict."
  [{:keys [identity] :as cl} job verdict verdict-cid receipt-cid snapshot-cid]
  (let [signed {:job-id (:job-id job) :rid (:repo job) :ref (:sha job) :verdict-cid verdict-cid}]
    (post cl "/v1/complete"
          (assoc (select-keys job [:job-id :token])
                 :verdict verdict :signed signed
                 :signature (crypto/sign identity (c/canonical signed))
                 :receipt-cid receipt-cid :snapshot-cid snapshot-cid))))

(defn get-blob
  "Fetch a CAS blob with the lease; the sha256 is checked before returning."
  [{:keys [base identity]} job sha]
  (let [res (http/get (str base "/v1/blob/" sha)
                      {:headers {"x-tatara-job" (:job-id job) "x-tatara-runner" (:did identity)
                                 "x-tatara-token" (:token job)}
                       :as :bytes :throw false})]
    (cond
      (not= 200 (:status res)) {:status (:status res)}
      (not= sha (crypto/sha256-bytes (:body res))) {:status 200 :error :blob-hash-mismatch}
      :else {:status 200 :bytes (:body res)})))
