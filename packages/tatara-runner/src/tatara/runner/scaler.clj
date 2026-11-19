(ns tatara.runner.scaler
  "Queue-based scaler for the Kubernetes runner StatefulSet (AWAI-3542).

  Every poll it reads the coordinator's GET /v1/runs and counts demand: replica
  jobs that are :queued or :leased in runs that are still :pending. It then sets
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

(defn- runs [coordinator]
  (let [r (p/shell {:out :string :err :string :continue true}
                   "curl" "-sS" "-f" "-m" "10" (str coordinator "/v1/runs"))]
    (when-not (zero? (:exit r))
      (throw (ex-info "coordinator unreachable" {:err (:err r)})))
    (edn/read-string (:out r))))

(defn -main
  "bb scale SCALER.edn — {:coordinator url :namespace ns :statefulset name
  :min-replicas 0 :max-replicas 3 :idle-seconds 600 :poll-seconds 30}"
  [config-file]
  (let [{:keys [coordinator namespace statefulset poll-seconds idle-seconds] :as cfg}
        (merge {:min-replicas 0 :max-replicas 3 :idle-seconds 600 :poll-seconds 30}
               (edn/read-string (slurp config-file)))
        path (str "/apis/apps/v1/namespaces/" namespace "/statefulsets/" statefulset "/scale")
        opts (assoc cfg :idle-ms (* 1000 idle-seconds))]
    (println "scaler" statefulset (select-keys cfg [:min-replicas :max-replicas :idle-seconds]))
    (loop [idle-since nil]
      (let [next-idle
            (try
              (let [current (get-in (kube "GET" path) [:spec :replicas] 0)
                    d (demand (runs coordinator))
                    {:keys [replicas idle-since]} (decide opts {:current current :demand d
                                                                :idle-since idle-since
                                                                :now (System/currentTimeMillis)})]
                (when (not= replicas current)
                  (kube "PATCH" path {:spec {:replicas replicas}})
                  (println (str (java.time.Instant/now)) "demand" d "replicas" current "->" replicas))
                idle-since)
              ;; Coordinator or API errors leave the replica count as it is.
              (catch Exception e
                (println (str (java.time.Instant/now)) "skip:" (ex-message e) (pr-str (ex-data e)))
                idle-since))]
        (Thread/sleep (* 1000 poll-seconds))
        (recur next-idle)))))
