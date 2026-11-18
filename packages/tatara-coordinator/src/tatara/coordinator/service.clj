(ns tatara.coordinator.service
  "Coordinator state transitions over tatara-core. Pure: every function takes
  the state and returns {:state s :reply r}; the caller persists :state before
  sending :reply. I/O (CAS, clock, randomness) arrives as arguments.

  State:
  {:requests {run-id RunRequest}                ; tatara.core.run/admit
   :runs {run-id {:repo :sha :ref :pipeline :pipeline-cid :threshold
                  :created ms :status :pending|:reached|:hard-error
                  :entries [{:runner :verdict-cid :result}] :outcome {...}}}
   :jobs {job-id {...}}                         ; tatara.core.lease
   :outbox {:actions {} :delivered #{}}}        ; tatara.core.outbox"
  (:require [tatara.core.canonical :as c]
            [tatara.core.lease :as lease]
            [tatara.core.outbox :as outbox]
            [tatara.core.run :as run]
            [tatara.core.verdict :as v]
            [tatara.coordinator.crypto :as crypto]))

(def ctx crypto/ctx)
(def empty-state {:requests {} :runs {} :jobs {} :outbox {:actions {} :delivered #{}}})
(def status-context "tatara/ci")

(defn- enqueue [state action]
  (assoc state :outbox (:outbox (outbox/enqueue ctx (:outbox state) action))))

(defn- status-action [run-id {:keys [repo sha]} state description]
  {:kind :github-status :run-id run-id :repo repo :sha sha
   :state state :context status-context :description description})

(defn submit-run
  "event: {:forge :repo :ref :sha :pipeline-cid}; pipeline must hash to its CID.
  Duplicate events of the same commit and pipeline return the existing run."
  [{:keys [default-threshold]} state {:keys [event pipeline]} now]
  (cond
    (not (map? pipeline)) {:state state :reply {:error :missing-pipeline}}
    (not= (:pipeline-cid event) (c/cid ctx pipeline)) {:state state :reply {:error :pipeline-cid-mismatch}}
    :else
    (let [n (:ok (run/normalize ctx event))]
      (if-not n
        {:state state :reply (run/normalize ctx event)}
        (let [{dup? :duplicate? id :run-id requests :state} (run/admit {:runs (:requests state)} n)]
          (if dup?
            {:state state :reply {:run-id id :duplicate? true}}
            (let [threshold (max 1 (or (:quorum pipeline) default-threshold 2))
                  r {:repo (:repo event) :sha (:sha event) :ref (:ref event)
                     :pipeline pipeline :pipeline-cid (:pipeline-cid event)
                     :threshold threshold :created now :status :pending :entries []}]
              {:state (-> state
                          (assoc :requests (:runs requests))
                          (assoc-in [:runs id] r)
                          (update :jobs merge (lease/expand-replicas ctx id threshold))
                          (enqueue (status-action id r "pending" "Tatara: waiting for runners")))
               :reply {:run-id id :duplicate? false}})))))))

(defn lease-job
  "Give the runner the first claimable replica of the oldest pending run whose
  tier it supports. token is a fresh random bearer secret; only its hash is kept."
  [{:keys [allow ttl-ms]} state {:keys [runner tiers]} token now]
  (if-not (contains? allow runner)
    {:state state :reply {:error :runner-not-allowed}}
    (let [tiers (set tiers)
          candidates (->> (:runs state)
                          (filter (fn [[_ r]] (and (= :pending (:status r))
                                                   (contains? tiers (get-in r [:pipeline :tier] :t2)))))
                          (sort-by (fn [[id r]] [(:created r) id])))]
      (loop [[[id r] & more] candidates]
        (if-not id
          {:state state :reply {:error :no-job}}
          (let [res (lease/claim ctx state {:run-id id :runner runner :token token :now now :ttl ttl-ms})]
            (if (:error res)
              (recur more)
              {:state (:state res)
               :reply {:job-id (:job-id res) :token token :ttl-ms ttl-ms :run-id id
                       :repo (:repo r) :sha (:sha r) :ref (:ref r)
                       :pipeline (:pipeline r) :pipeline-cid (:pipeline-cid r)}})))))))

(defn heartbeat [{:keys [ttl-ms]} state {:keys [job-id runner token]} now]
  (let [chk (lease/check ctx state {:job-id job-id :runner runner :token token :now now})]
    (if (:error chk)
      {:state state :reply chk}
      {:state (assoc-in state [:jobs job-id :expires] (+ now ttl-ms))
       :reply {:ok true :expires (+ now ttl-ms)}})))

(defn check-lease [state presentation now]
  (lease/check ctx state (assoc presentation :now now)))

(defn object-cid [value] (c/cid ctx value))

(defn- signature-ok? [sub]
  (and (= (:job-id sub) (get-in sub [:signed :job-id]))
       (crypto/verify (:runner sub) (c/canonical (:signed sub)) (:signature sub))))

(defn- outcome-action [run-id r q]
  (case (:status q)
    :reached (let [result (some #(when (= (:verdict-cid q) (:verdict-cid %)) (:result %)) (:entries r))]
               (status-action run-id r (if (= :passed result) "success" "failure")
                              (str "Tatara: " (name result) " by " (count (:runners q)) " runners")))
    :hard-error (status-action run-id r "error" (str "Tatara: " (name (:reason q))))
    nil))

(defn complete
  "sub: {:job-id :runner :token :verdict :signed {:job-id :rid :ref :verdict-cid}
         :signature :receipt-cid :snapshot-cid}.
  Admission is tatara.core.verdict/admit-completion; then quorum decides."
  [{:keys [allow]} state sub now cas-has?]
  (let [job (get-in state [:jobs (:job-id sub)])
        r (get-in state [:runs (:run-id job)])
        adm (when r
              (v/admit-completion (assoc ctx :allow allow :cas-has? cas-has? :verify signature-ok?)
                                  state {:rid (:repo r) :ref (:sha r)} (assoc sub :now now)))]
    (cond
      (nil? r) {:state state :reply {:error :unknown-job}}
      (:error adm) {:state state :reply adm}
      (not= :pending (:status r)) {:state state :reply {:error :run-closed :status (:status r)}}
      :else
      (let [run-id (:run-id job)
            entry {:runner (:runner sub) :verdict-cid (get-in adm [:ok :verdict-cid])
                   :result (get-in sub [:verdict :result])}
            r2 (update r :entries conj entry)
            q (v/quorum (:threshold r2) (:entries r2))
            r3 (cond-> r2 (not= :pending (:status q)) (assoc :status (:status q) :outcome q))
            act (when (not= :pending (:status q)) (outcome-action run-id r3 q))]
        {:state (cond-> (-> state
                            (assoc-in [:jobs (:job-id sub) :status] :done)
                            (assoc-in [:runs run-id] r3))
                  act (enqueue act))
         :reply {:ok true :run-status (:status r3) :quorum q}}))))

(defn pending-actions [state] (outbox/pending (:outbox state)))

(defn mark-delivered [state k]
  (let [r (outbox/mark-delivered (:outbox state) k)]
    (if (:error r) state (assoc state :outbox (:outbox r)))))

(defn run-view [state run-id]
  (when-let [r (get-in state [:runs run-id])]
    (assoc (select-keys r [:repo :sha :ref :pipeline-cid :threshold :status :entries :outcome :created])
           :run-id run-id
           :replicas (->> (:jobs state) vals (filter #(= run-id (:run-id %)))
                          (map #(select-keys % [:index :status :runner])) (sort-by :index) vec))))
