(ns tatara.runner.burst
  "On-demand node pool for heavy builds (AWAI-3834).

  VKE node pools cannot scale up from 0, and a min-1 vc2-4c-8gb pool would
  cost $40/month for a lane that is idle most days. So this manager creates a
  dedicated, tainted pool when the coordinator has demand for the lane's
  toolchain, and deletes it after :idle-seconds without demand:
  - demand = queued + leased replica jobs of :pending runs that need
    :toolchain (GET /v1/queue?tier=t2&toolchain=...). An unreachable
    coordinator counts as no demand, so an outage cannot hold the pool.
  - It only ever creates, reads or deletes the pool whose label is :label AND
    whose Kubernetes node labels carry awai.network/burst=<label>. Every
    other pool (tatara-system, the runner pool, release builds, acme) is
    never touched; this is checked before every delete.
  - The pool carries awai.network/reapable=true and no tatara* name or
    tatara.network/role label, so the VKE build-reaper (AWAI-3664, awai-os
    deploy/build-reaper) is the backstop if this manager dies: it deletes such
    a pool after 30 min with no Pod selecting vke.vultr.com/node-pool=<label>.
    The APK runner pod selects the pool that way, so it counts as activity.
  - :max-pool-hours bounds a pool's life even with demand (a stuck run).
  Every create and delete is logged with the reason."
  (:require [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]))

(def vultr "https://api.vultr.com/v2")

(defn pool-spec
  "The create body for the lane's pool."
  [{:keys [label plan]}]
  {:node_quantity 1 :min_nodes 1 :max_nodes 1 :auto_scaler false
   :label label :plan plan :tag label
   :labels {"awai.network/burst" label
            "awai.network/purpose" "ci-burst" "awai.network/reapable" "true"}
   :taints [{:key "awai.network/apk" :value "build" :effect "NoSchedule"}]})

(defn ours?
  "Only the lane's own pool: same label, and our node labels."
  [{:keys [label]} pool]
  (and (= label (:label pool))
       (= label (get-in pool [:labels :awai.network/burst] (get-in pool [:labels "awai.network/burst"])))))

(defn decide
  "Pure decision. state {:idle-since ms|nil}; obs {:demand n :pools [...] :now ms}.
  Returns {:action :create|:delete|:none :reason str :idle-since ms|nil}."
  [{:keys [idle-ms max-pool-ms] :as cfg} {:keys [idle-since]} {:keys [demand pools now]}]
  (let [mine (filter #(ours? cfg %) pools)
        pool (first mine)
        created (some-> (:date_created pool) java.time.OffsetDateTime/parse .toInstant .toEpochMilli)
        idle-since (if (pos? demand) nil (or idle-since now))]
    (cond
      (> (count mine) 1) {:action :delete :pool (last (sort-by :date_created mine))
                          :reason "more than one lane pool" :idle-since idle-since}
      (and pool created (> (- now created) max-pool-ms))
      {:action :delete :pool pool :reason "pool older than max-pool-hours" :idle-since idle-since}
      (and (pos? demand) (nil? pool)) {:action :create :reason (str "demand " demand) :idle-since nil}
      (and pool (zero? demand) (>= (- now idle-since) idle-ms))
      {:action :delete :pool pool :reason (str "no demand for " (quot (- now idle-since) 1000) " s") :idle-since idle-since}
      :else {:action :none :idle-since idle-since})))

(defn- api [{:keys [token]} method path & [body]]
  (let [r (http/request {:method method :uri (str vultr path) :throw false :timeout 30000
                         :headers (cond-> {"authorization" (str "Bearer " @token)}
                                    body (assoc "content-type" "application/json"))
                         :body (when body (json/generate-string body))})]
    (when-not (<= 200 (:status r) 299)
      (throw (ex-info "vultr API call failed" {:method method :path path :status (:status r)})))
    (when (seq (:body r)) (json/parse-string (:body r) true))))

(defn pools [{:keys [cluster] :as cfg}]
  (:node_pools (api cfg :get (str "/kubernetes/clusters/" cluster "/node-pools?per_page=100"))))

(defn- demand [coordinator toolchain]
  (let [r (p/shell {:out :string :err :string :continue true} "curl" "-sS" "-f" "-m" "10"
                   (str coordinator "/v1/queue?tier=t2&toolchain=" (name toolchain)))]
    (if (zero? (:exit r))
      (let [{:keys [queued leased]} (edn/read-string (:out r))] (+ queued leased))
      (do (println (str (java.time.Instant/now)) "coordinator unreachable; counting no demand") 0))))

(defn act!
  "Carry out a decision. A delete re-reads the pool list and re-checks
  ownership first. Returns the decision."
  [{:keys [cluster dry-run?] :as cfg} d]
  (case (:action d)
    :create (do (println (str (java.time.Instant/now)) "create pool" (:label cfg) (:plan cfg) "-" (:reason d)
                         (when dry-run? "(dry run)"))
                (when-not dry-run?
                  (api cfg :post (str "/kubernetes/clusters/" cluster "/node-pools") (pool-spec cfg))))
    :delete (let [id (get-in d [:pool :id])
                  current (some #(when (= id (:id %)) %) (pools cfg))]
              (if-not (and current (ours? cfg current))
                (println (str (java.time.Instant/now)) "refusing to delete pool" id "- not the lane's own pool")
                (do (println (str (java.time.Instant/now)) "delete pool" id (:label current) "-" (:reason d)
                             (when dry-run? "(dry run)"))
                    (when-not dry-run?
                      (api cfg :delete (str "/kubernetes/clusters/" cluster "/node-pools/" id))))))
    nil)
  d)

(defn -main
  "bb burst BURST.edn — {:coordinator url :toolchain :android-v1 :cluster \"<vke id>\"
  :label \"awai-apk-burst\" :plan \"vc2-4c-8gb\" :idle-seconds 900 :max-pool-hours 4
  :poll-seconds 30 :token-file \"/secrets/vultr/api-key\" :dry-run? false}"
  [config-file]
  (let [cfg (merge {:idle-seconds 900 :max-pool-hours 4 :poll-seconds 30 :plan "vc2-4c-8gb"}
                   (edn/read-string (slurp config-file)))
        cfg (assoc cfg :idle-ms (* 1000 (:idle-seconds cfg)) :max-pool-ms (* 3600000 (:max-pool-hours cfg))
                   :token (delay (clojure.string/trim (slurp (:token-file cfg)))))]
    (println "burst" (select-keys cfg [:label :plan :toolchain :idle-seconds :max-pool-hours :dry-run?]))
    (loop [st {:idle-since nil}]
      (let [st (try
                 (let [d (decide cfg st {:demand (demand (:coordinator cfg) (:toolchain cfg))
                                         :pools (pools cfg) :now (System/currentTimeMillis)})]
                   (act! cfg d)
                   {:idle-since (:idle-since d)})
                 (catch Exception e
                   (println (str (java.time.Instant/now)) "skip:" (ex-message e) (pr-str (ex-data e)))
                   st))]
        (Thread/sleep (* 1000 (:poll-seconds cfg)))
        (recur st)))))
