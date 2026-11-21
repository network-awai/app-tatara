(ns tatara.coordinator.http
  "HTTP surface of the coordinator (EDN bodies). Runner requests carry an
  Ed25519 signature over the exact body and a timestamp; job operations also
  present the lease token. Every mutation is persisted before the reply."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [org.httpkit.server :as hs]
            [tatara.coordinator.crypto :as crypto]
            [tatara.coordinator.service :as svc]
            [tatara.coordinator.store :as store])
  (:import (java.security SecureRandom)))

(def max-body-bytes (* 1024 1024))
(def skew-ms 120000)

(defn- reply [status body]
  {:status status :headers {"content-type" "application/edn"} :body (pr-str body)})

(defn- token []
  (let [b (byte-array 32)] (.nextBytes (SecureRandom.) b) (crypto/hex b)))

(defn- read-bytes [req limit]
  (when-let [in (:body req)]
    (let [b (.readNBytes ^java.io.InputStream in (inc limit))]
      (when (> (count b) limit) (throw (ex-info "body too large" {:status 413})))
      b)))

(defn- signed-body
  "Parse and authenticate a runner request. Returns the body map or throws."
  [req now]
  (let [text (String. ^bytes (or (read-bytes req max-body-bytes) (byte-array 0)) "UTF-8")
        body (try (edn/read-string text) (catch Exception _ nil))
        sig (get-in req [:headers "x-tatara-signature"])]
    (when-not (and (map? body) (string? (:runner body)) (integer? (:ts body)))
      (throw (ex-info "malformed request" {:status 400})))
    (when-not (crypto/verify (:runner body) text sig)
      (throw (ex-info "bad signature" {:status 401})))
    (when (> (Math/abs (long (- now (:ts body)))) skew-ms)
      (throw (ex-info "stale request" {:status 401})))
    body))

(defn- mutate!
  "Apply f to the state under the lock, persist, then return the reply."
  [{:keys [state lock root]} f]
  (locking lock
    (let [{s :state r :reply} (f @state)]
      (when-not (identical? s @state)
        (store/save-state! root s)
        (reset! state s))
      r)))

(defn- status-of [r]
  (cond (nil? r) 500
        (#{:runner-not-allowed :bad-signature :stale-token :wrong-runner} (:error r)) 403
        (= :no-job (:error r)) 200
        (:error r) 409
        :else 200))

(defn- cas-has? [root cid]
  (and (store/has-object? root cid)
       (let [v (store/read-object root cid)]
         (if-let [sha (:tatara/blob v)] (store/has-blob? root sha) true))))

(defn handler [{:keys [cfg state root clock admin-token] :as srv}]
  (fn [req]
    (try
      (let [now (clock) uri (:uri req) m (:request-method req)]
        (cond
          (and (= m :post) (= uri "/v1/lease"))
          (let [b (signed-body req now)
                r (mutate! srv #(svc/lease-job cfg % b (token) now (:remote-addr req)))]
            (reply (status-of r) r))

          (and (= m :post) (= uri "/v1/heartbeat"))
          (let [b (signed-body req now)
                r (mutate! srv #(svc/heartbeat cfg % b now))]
            (reply (status-of r) r))

          (and (= m :put) (str/starts-with? uri "/v1/blob/"))
          (let [sha (subs uri (count "/v1/blob/"))
                h (:headers req)
                pres {:job-id (get h "x-tatara-job") :runner (get h "x-tatara-runner")
                      :token (get h "x-tatara-token")}
                chk (svc/check-lease @state pres now)]
            (cond
              (not (re-matches #"[0-9a-f]{64}" sha)) (reply 400 {:error :bad-sha})
              (:error chk) (reply 403 chk)
              :else (let [data (or (read-bytes req (:max-blob-bytes cfg)) (byte-array 0))]
                      (if (not= sha (crypto/sha256-bytes data))
                        (reply 409 {:error :blob-hash-mismatch})
                        (do (store/put-blob! root sha data) (reply 200 {:ok true :sha sha}))))))

          (and (= m :get) (str/starts-with? uri "/v1/blob/"))
          (let [sha (subs uri (count "/v1/blob/"))
                h (:headers req)
                chk (svc/check-lease @state {:job-id (get h "x-tatara-job") :runner (get h "x-tatara-runner")
                                             :token (get h "x-tatara-token")} now)]
            (cond
              (not (re-matches #"[0-9a-f]{64}" sha)) (reply 400 {:error :bad-sha})
              (:error chk) (reply 403 chk)
              (not (store/has-blob? root sha)) (reply 404 {:error :unknown-blob})
              :else {:status 200 :headers {"content-type" "application/octet-stream"}
                     :body (java.io.File. (str (store/blob-path root sha)))}))

          (and (= m :post) (= uri "/v1/object"))
          (let [b (signed-body req now)
                chk (svc/check-lease @state (select-keys b [:job-id :runner :token]) now)
                v (:value b)]
            (cond
              (:error chk) (reply 403 chk)
              (and (:tatara/blob v) (not (store/has-blob? root (:tatara/blob v)))) (reply 409 {:error :blob-missing})
              :else (let [cid (svc/object-cid v)]
                      (store/put-object! root cid (tatara.core.canonical/canonical v) v)
                      (reply 200 {:ok true :cid cid}))))

          (and (= m :post) (= uri "/v1/complete"))
          (let [b (signed-body req now)
                r (mutate! srv #(svc/complete cfg % (dissoc b :ts) now (partial cas-has? root) (:remote-addr req)))]
            (reply (status-of r) r))

          (and (= m :post) (= uri "/v1/runs"))
          (if-not (and admin-token (= (str "Bearer " admin-token) (get-in req [:headers "authorization"])))
            (reply 401 {:error :unauthorized})
            (let [b (edn/read-string (String. ^bytes (read-bytes req max-body-bytes) "UTF-8"))
                  r (mutate! srv #(svc/submit-run cfg % b now))]
              (reply (status-of r) r)))

          (and (= m :post) (= uri "/v1/admin/expire"))
          (if-not (and admin-token (= (str "Bearer " admin-token) (get-in req [:headers "authorization"])))
            (reply 401 {:error :unauthorized})
            (let [b (edn/read-string (String. ^bytes (or (read-bytes req max-body-bytes) (byte-array 0)) "UTF-8"))
                  ttl (:older-than-ms b)]
              (if-not (and (integer? ttl) (not (neg? ttl)))
                (reply 400 {:error :bad-older-than-ms})
                (reply 200 (mutate! srv #(svc/expire-stale % now ttl))))))

          (and (= m :post) (= uri "/v1/admin/rerun"))
          (if-not (and admin-token (= (str "Bearer " admin-token) (get-in req [:headers "authorization"])))
            (reply 401 {:error :unauthorized})
            (let [b (edn/read-string (String. ^bytes (or (read-bytes req max-body-bytes) (byte-array 0)) "UTF-8"))
                  ids (:run-ids b)]
              (if-not (and (vector? ids) (every? string? ids))
                (reply 400 {:error :bad-run-ids})
                (reply 200 (mutate! srv #(svc/rerun % ids now))))))

          (and (= m :get) (= uri "/v1/metrics/dora"))
          (let [days (or (some-> (second (re-find #"(?:^|&)days=([0-9]+)" (or (:query-string req) ""))) parse-long) 7)]
            (reply 200 {:window-days days :at now :repos (svc/dora @state now (* days 86400000))}))

          (and (= m :get) (= uri "/v1/queue"))
          (let [tier (keyword (or (second (re-find #"(?:^|&)tier=([a-z0-9-]+)" (or (:query-string req) ""))) "t2"))]
            (reply 200 (svc/queue-depth @state now tier)))

          (and (= m :get) (= uri "/v1/runs"))
          (reply 200 (->> (:runs @state) keys (map #(svc/run-view @state %)) (sort-by :created) vec))

          ;; The step log of one replica, so a failed status links to why (AWAI-3799).
          (and (= m :get) (re-matches #"/v1/runs/[^/]+/log/[0-9]+" uri))
          (let [[_ id idx] (re-matches #"/v1/runs/([^/]+)/log/([0-9]+)" uri)
                job (some #(when (and (= id (:run-id %)) (= (parse-long idx) (:index %))) %) (vals (:jobs @state)))
                receipt (some->> (:receipt-cid job) (store/read-object root))
                sha (:log-sha256 receipt)]
            (if (and sha (store/has-blob? root sha))
              {:status 200 :headers {"content-type" "text/plain; charset=utf-8"}
               :body (java.io.File. (str (store/blob-path root sha)))}
              (reply 404 {:error :unknown-log})))

          (and (= m :get) (str/starts-with? uri "/v1/runs/") (str/ends-with? uri "/bundle"))
          (let [id (subs uri (count "/v1/runs/") (- (count uri) (count "/bundle")))]
            (if-let [b (svc/run-bundle cfg @state id #(store/read-object root %))]
              (reply 200 b) (reply 404 {:error :unknown-run})))

          (and (= m :get) (str/starts-with? uri "/v1/runs/"))
          (let [snapshot @state
                id (subs uri (count "/v1/runs/"))]
            (if-let [v (svc/run-view snapshot id)]
              (reply 200 (assoc v :timings (svc/run-timings snapshot id #(store/read-object root %))))
              (reply 404 {:error :unknown-run})))

          (and (= m :get) (str/starts-with? uri "/v1/object/"))
          (if-let [v (store/read-object root (subs uri (count "/v1/object/")))]
            (reply 200 v) (reply 404 {:error :unknown-object}))

          :else (reply 404 {:error :not-found})))
      (catch clojure.lang.ExceptionInfo e
        (reply (or (:status (ex-data e)) 400) {:error (.getMessage e)})))))

(defn start!
  "cfg: {:root dir :port n :bind host :allow #{did} :ttl-ms :default-threshold
         :max-blob-bytes}. Restores state from root. Returns the server map."
  [{:keys [root port bind] :as cfg} {:keys [clock admin-token] :or {clock #(System/currentTimeMillis)}}]
  (let [cfg (merge {:ttl-ms 120000 :default-threshold 2 :max-blob-bytes (* 256 1024 1024)} cfg)
        srv {:cfg cfg :root root :clock clock :admin-token admin-token :lock (Object.)
             :state (atom (or (store/load-state root) svc/empty-state))}
        stop (hs/run-server (handler srv) {:port (or port 0) :ip (or bind "127.0.0.1")
                                           :max-body (+ (:max-blob-bytes cfg) 1024)
                                           :legacy-return-value? false})]
    (assoc srv :server stop :port (hs/server-port stop))))

(defn stop! [srv] (hs/server-stop! (:server srv)))

(defn put-blob!
  "In-process blob admission (source snapshots fetched by the poller)."
  [srv ^bytes data]
  (let [sha (crypto/sha256-bytes data)]
    (store/put-blob! (:root srv) sha data)
    {:sha256 sha :size (count data)}))

(defn submit!
  "In-process run submission (the GitHub poller). Persisted before returning."
  [srv body]
  (mutate! srv #(svc/submit-run (:cfg srv) % body ((:clock srv)))))

(defn expire-stale!
  "Close :pending runs older than ttl-ms (svc/expire-stale). Persisted."
  [srv ttl-ms]
  (mutate! srv #(svc/expire-stale % ((:clock srv)) ttl-ms)))

(defn gc-snapshots!
  "Delete source-snapshot blobs of runs closed longer than retention-ms ago
  (svc/collectable-snapshots). Returns the number of blobs removed."
  [srv retention-ms]
  (let [shas (svc/collectable-snapshots @(:state srv) ((:clock srv)) retention-ms)]
    (count (filter #(store/delete-blob! (:root srv) %) shas))))

(defn mark-delivered!
  "Record that an outbox action reached its destination. Persisted."
  [srv k]
  (mutate! srv (fn [s] {:state (svc/mark-delivered s k) :reply {:ok true}})))
