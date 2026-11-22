(ns tatara.runner.scaler
  "Queue-based scaler for the Kubernetes runner StatefulSet (AWAI-3542).

  Every poll it reads the coordinator's GET /v1/queue: replica jobs of :pending
  :t2 runs that are queued or hold a live lease. An unreachable coordinator
  counts as no demand. It then sets
  the StatefulSet's replica count through the Kubernetes scale subresource:
  - up at once, to (min demand max-replicas), never below the current count;
  - down only to min-replicas, and only after demand has been 0 for
    :idle-seconds, so a pod is not removed while it holds a lease.
  It talks to the API server with the pod's service account and curl."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn demand
  "Replica jobs still waiting for or held by a runner, over :pending runs."
  [runs]
  (->> runs
       (filter #(= :pending (:status %)))
       (mapcat :replicas)
       (filter #(contains? #{:queued :leased} (:status %)))
       count))

(defn decide
  "Pure scaling decision. idle-since is the time demand was first seen at 0
  (nil while there is demand). Returns {:replicas n :idle-since t}."
  [{:keys [min-replicas max-replicas idle-ms]} {:keys [current demand idle-since now]}]
  (let [floor (max current min-replicas)]
    (cond
      (pos? demand) {:replicas (max floor (min max-replicas demand)) :idle-since nil}
      (nil? idle-since) {:replicas floor :idle-since now}
      (>= (- now idle-since) idle-ms) {:replicas min-replicas :idle-since idle-since}
      :else {:replicas floor :idle-since idle-since})))

(def ^:private sa "/var/run/secrets/kubernetes.io/serviceaccount")

(defn- kube [method path & [body]]
  (let [token (str/trim (slurp (str sa "/token")))
        url (str "https://" (System/getenv "KUBERNETES_SERVICE_HOST") ":"
                 (System/getenv "KUBERNETES_SERVICE_PORT") path)
        args (cond-> ["curl" "-sS" "-f" "-m" "10" "--cacert" (str sa "/ca.crt")
                      "-H" (str "Authorization: Bearer " token) "-X" method]
               body (into ["-H" "Content-Type: application/merge-patch+json" "--data" (json/generate-string body)])
               true (conj url))
        r (apply p/shell {:out :string :err :string :continue true} args)]
    (when-not (zero? (:exit r))
      (throw (ex-info "kubernetes API call failed" {:path path :err (:err r)})))
    (json/parse-string (:out r) true)))

(defn- queue
  "Demand from the coordinator's small GET /v1/queue (queued + live leases of
  :t2 runs without a toolchain). Expired leases are already counted as queued
  there, and runs past their TTL are closed, so lost leases do not hold nodes."
  [coordinator & [toolchain]]
  (let [r (p/shell {:out :string :err :string :continue true}
                   "curl" "-sS" "-f" "-m" "10"
                   (str coordinator "/v1/queue?tier=t2" (when toolchain (str "&toolchain=" (name toolchain)))))]
    (when-not (zero? (:exit r))
      (throw (ex-info "coordinator unreachable" {:err (:err r)})))
    (let [{:keys [queued leased]} (edn/read-string (:out r))]
      (+ queued leased))))

(defn observed-demand
  "Demand to act on. An unreachable coordinator counts as 0: runners cannot
  lease or complete without it, so after :idle-seconds of outage the pool
  falls to the minimum instead of holding nodes (AWAI-3799: 276 unreachable
  polls in 24 h kept 3 replicas and 2 nodes up)."
  [read-demand]
  (try {:demand (read-demand)}
       (catch Exception e {:demand 0 :error (ex-message e) :data (ex-data e)})))

(defn targets
  "One runner identity per StatefulSet (AWAI-3799): n replicas means the first
  n StatefulSets at 1 and the rest at 0. Returns {name 0|1}."
  [statefulsets n]
  (into {} (map-indexed (fn [i sts] [sts (if (< i n) 1 0)]) statefulsets)))

(defn -main
  "bb scale SCALER.edn — {:coordinator url :namespace ns
  :statefulsets [\"tatara-runner-0\" ...] :min-replicas 0 :max-replicas 3
  :idle-seconds 600 :poll-seconds 30}. With :toolchain :android-v1 it counts only runs
  that need that toolchain (AWAI-3834); without, only runs that need none. :statefulset name (one StatefulSet with
  n replicas) is still accepted for the old layout."
  [config-file]
  (let [{:keys [coordinator namespace statefulset statefulsets poll-seconds idle-seconds] :as cfg}
        (merge {:min-replicas 0 :max-replicas 3 :idle-seconds 600 :poll-seconds 30}
               (edn/read-string (slurp config-file)))
        path #(str "/apis/apps/v1/namespaces/" namespace "/statefulsets/" % "/scale")
        names (vec (or statefulsets [statefulset]))
        split? (some? statefulsets)
        opts (cond-> (assoc cfg :idle-ms (* 1000 idle-seconds))
               split? (update :max-replicas min (count names)))]
    (println "scaler" names (select-keys opts [:min-replicas :max-replicas :idle-seconds]))
    (loop [idle-since nil]
      (let [next-idle
            (try
              (let [now-counts (into {} (map (fn [n] [n (get-in (kube "GET" (path n)) [:spec :replicas] 0)])) names)
                    current (reduce + (vals now-counts))
                    {d :demand err :error data :data} (observed-demand #(queue coordinator (:toolchain cfg)))
                    _ (when err (println (str (java.time.Instant/now)) "coordinator:" err (pr-str data)))
                    {:keys [replicas idle-since]} (decide opts {:current current :demand d
                                                                :idle-since idle-since
                                                                :now (System/currentTimeMillis)})
                    want (if split? (targets names replicas) {(first names) replicas})]
                (doseq [[n r] want :when (not= r (get now-counts n))]
                  (kube "PATCH" (path n) {:spec {:replicas r}}))
                (when (not= replicas current)
                  (println (str (java.time.Instant/now)) "demand" d "replicas" current "->" replicas))
                idle-since)
              ;; Kubernetes API errors leave the replica count as it is.
              (catch Exception e
                (println (str (java.time.Instant/now)) "skip:" (ex-message e) (pr-str (ex-data e)))
                idle-since))]
        (Thread/sleep (* 1000 poll-seconds))
        (recur next-idle)))))
