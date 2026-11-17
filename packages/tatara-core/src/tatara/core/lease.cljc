(ns tatara.core.lease
  "Replica jobs and leases. Only a hash of the bearer token is stored.
  State: {:jobs {job-id {:run-id :index :status :queued|:leased|:done
                         :runner :token-hash :expires}}}"
  (:require [tatara.core.canonical :as c]))

(defn expand-replicas [ctx run-id n]
  (into {} (map (fn [i] [(c/cid ctx [run-id i])
                         {:run-id run-id :index i :status :queued}]))
        (range n)))

(defn- claimable? [job now]
  (or (= :queued (:status job))
      (and (= :leased (:status job)) (<= (:expires job) now))))

(defn claim
  "Lease the first claimable job of run-id to runner. A runner DID may take at
  most one replica of a logical run, even after its lease expired."
  [{:keys [hash]} state {:keys [run-id runner token now ttl]}]
  (let [jobs (:jobs state)
        used? (some #(and (= run-id (:run-id %)) (= runner (:runner %)) (:runner %))
                    (vals jobs))]
    (if used?
      {:error :runner-already-used}
      (if-let [[jid _] (->> jobs
                            (filter (fn [[_ j]] (and (= run-id (:run-id j)) (claimable? j now))))
                            (sort-by (fn [[_ j]] (:index j)))
                            first)]
        {:state (update-in state [:jobs jid] assoc
                           :status :leased :runner runner
                           :token-hash (hash token) :expires (+ now ttl))
         :job-id jid}
        {:error :no-job}))))

(defn check
  "Validate a lease presentation for job-id."
  [{:keys [hash]} state {:keys [job-id runner token now]}]
  (let [j (get-in state [:jobs job-id])]
    (cond
      (nil? j) {:error :unknown-job}
      (not= :leased (:status j)) {:error :not-leased}
      (not= runner (:runner j)) {:error :wrong-runner}
      (not= (hash token) (:token-hash j)) {:error :stale-token}
      (<= (:expires j) now) {:error :lease-expired}
      :else {:ok j})))
