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
  snapshot (optional): {:sha256 :size} of the source tarball already in the CAS.
  Duplicate events of the same commit and pipeline return the existing run."
  [{:keys [default-threshold]} state {:keys [event pipeline snapshot]} now]
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
                     :threshold threshold :created now :status :pending :entries []
                     :snapshot snapshot}]
              {:state (-> state
                          (assoc :requests (:runs requests))
                          (assoc-in [:runs id] r)
                          (update :jobs merge (lease/expand-replicas ctx id threshold))
                          (enqueue (status-action id r "pending" "Tatara: waiting for runners")))
               :reply {:run-id id :duplicate? false}})))))))

(defn- origin-used?
  "With :distinct-origins?, a run's replicas must come from different network
  origins (pod or host address): two DIDs on one compromised pod or host must
  not form a quorum alone (AWAI-3799)."
  [{:keys [distinct-origins?]} state run-id runner origin]
  (and distinct-origins? origin
       (some #(and (= run-id (:run-id %)) (= origin (:origin %)) (not= runner (:runner %))
                   (contains? #{:leased :done} (:status %)))
             (vals (:jobs state)))))

(defn lease-job
  "Give the runner the first claimable replica of the oldest pending run whose
  tier and toolchain it supports. Toolchains are assigned by the operator.
  origin: the request's source address, recorded on the job."
  [{:keys [allow ttl-ms runner-toolchains] :as cfg} state {:keys [runner tiers]} token now & [origin]]
  (if-not (contains? allow runner)
    {:state state :reply {:error :runner-not-allowed}}
    (let [tiers (set tiers)
          toolchains (get runner-toolchains runner #{})
          candidates (->> (:runs state)
                          (filter (fn [[_ r]] (and (= :pending (:status r))
                                                   (contains? tiers (get-in r [:pipeline :tier] :t2))
                                                   (if-let [toolchain (get-in r [:pipeline :toolchain])]
                                                     (contains? toolchains toolchain)
                                                     true))))
                          (sort-by (fn [[id r]] [(:created r) id])))]
      (loop [[[id r] & more] candidates]
        (if-not id
          {:state state :reply {:error :no-job}}
          (let [res (if (origin-used? cfg state id runner origin)
                      {:error :origin-used}
                      (lease/claim ctx state {:run-id id :runner runner :token token :now now :ttl ttl-ms}))]
            (if (:error res)
              (recur more)
              {:state (update-in (:state res) [:jobs (:job-id res)] assoc :leased-at now :origin origin)
               :reply {:job-id (:job-id res) :token token :ttl-ms ttl-ms :run-id id
                       :repo (:repo r) :sha (:sha r) :ref (:ref r)
                       :pipeline (:pipeline r) :pipeline-cid (:pipeline-cid r)
                       :snapshot (:snapshot r)}})))))))

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
  [{:keys [allow] :as cfg} state sub now cas-has? & [origin]]
  (let [job (get-in state [:jobs (:job-id sub)])
        r (get-in state [:runs (:run-id job)])
        adm (when r
              (v/admit-completion (assoc ctx :allow allow :cas-has? cas-has? :verify signature-ok?)
                                  state {:rid (:repo r) :ref (:sha r)} (assoc sub :now now)))]
    (cond
      (nil? r) {:state state :reply {:error :unknown-job}}
      (:error adm) {:state state :reply adm}
      ;; The verdict must come from where the lease went, and no other DID of
      ;; this run may have completed from the same origin.
      (and (:distinct-origins? cfg) (not= origin (:origin job))) {:state state :reply {:error :origin-mismatch}}
      (origin-used? cfg (update state :jobs dissoc (:job-id sub)) (:run-id job) (:runner sub) origin)
      {:state state :reply {:error :origin-used}}
      (not= :pending (:status r)) {:state state :reply {:error :run-closed :status (:status r)}}
      :else
      (let [run-id (:run-id job)
            entry {:runner (:runner sub) :verdict-cid (get-in adm [:ok :verdict-cid])
                   :result (get-in sub [:verdict :result])}
            ;; Keep the signed submission so a device can re-check the run
            ;; without trusting this server (run-bundle, AWAI-3410).
            r2 (-> r (update :entries conj entry)
                   (update :submissions (fnil conj []) (select-keys sub [:runner :verdict :signed :signature])))
            q (v/quorum (:threshold r2) (:entries r2))
            r3 (cond-> r2 (not= :pending (:status q)) (assoc :status (:status q) :outcome q :quorum-at now))
            act (when (not= :pending (:status q)) (outcome-action run-id r3 q))]
        {:state (cond-> (-> state
                            (update-in [:jobs (:job-id sub)] assoc
                                       :status :done :completed-at now :receipt-cid (:receipt-cid sub))
                            (assoc-in [:runs run-id] r3))
                  act (enqueue act))
         :reply {:ok true :run-status (:status r3) :quorum q}}))))

(defn rerun
  "Reopen closed runs so they run again with fresh replica jobs (operator
  action, e.g. after a runner-side fault; AWAI-3799). The run id is the hash of
  its event, so the run is reset in place; the earlier outcome, entries and
  submissions are kept under :previous. Pending or unknown runs are skipped.
  Returns {:state s :reply {:rerun [ids] :skipped [ids]}}."
  [state run-ids now]
  (let [ok (filter #(when-let [r (get-in state [:runs %])] (not= :pending (:status r))) (distinct run-ids))]
    {:state (reduce (fn [s id]
                      (let [r (get-in s [:runs id])
                            prev (select-keys r [:status :outcome :entries :submissions :quorum-at :expired-at :created])
                            r2 (-> r (dissoc :outcome :quorum-at :expired-at :submissions)
                                   (assoc :status :pending :entries [] :created now)
                                   (update :previous (fnil conj []) prev))]
                        (-> s
                            (assoc-in [:runs id] r2)
                            (update :jobs merge (lease/expand-replicas ctx id (:threshold r)))
                            (enqueue (status-action id r2 "pending" "Tatara: rerun, waiting for runners")))))
                    state ok)
     :reply {:rerun (vec ok) :skipped (vec (remove (set ok) run-ids))}}))

(defn expire-stale
  "Close every :pending run created at or before (- now ttl-ms) as :hard-error
  :expired and queue an error status for it. Its open replica jobs become
  :expired, so they are no longer leased or counted as demand. A run that no
  runner can finish (leases lost while the coordinator was unreachable) would
  otherwise stay :pending forever and keep runner nodes up (AWAI-3799).
  Returns {:state s :reply {:expired [run-id ...]}}."
  [state now ttl-ms]
  (let [stale (->> (:runs state)
                   (filter (fn [[_ r]] (and (= :pending (:status r)) (integer? (:created r))
                                            (<= (:created r) (- now ttl-ms)))))
                   (map key) sort vec)
        ids (set stale)]
    {:state (if (empty? stale)
              state
              (reduce (fn [s id]
                        (let [r (assoc (get-in s [:runs id]) :status :hard-error
                                       :outcome {:status :hard-error :reason :expired} :expired-at now)]
                          (-> s (assoc-in [:runs id] r)
                              (enqueue (status-action id r "error"
                                                      "Tatara: expired without quorum; push again to rerun")))))
                      (update state :jobs
                              (fn [jobs] (into {} (map (fn [[jid j]]
                                                         [jid (if (and (ids (:run-id j)) (not= :done (:status j)))
                                                                (assoc j :status :expired) j)]))
                                               jobs)))
                      stale))
     :reply {:expired stale}}))

(defn collectable-snapshots
  "Source-snapshot blob shas that no run needs any more: every run that names
  the snapshot is closed, and closed at or before (- now retention-ms) (by
  quorum, hard error or expiry; :created when no close time was kept). The
  tarball can be fetched again from the forge; logs and artifacts are other
  blobs and are kept. Without this the CAS grew ~180 MB per awai-os run."
  [state now retention-ms]
  (let [by-sha (group-by #(get-in % [:snapshot :sha256]) (vals (:runs state)))]
    (->> by-sha
         (keep (fn [[sha runs]]
                 (when (and sha
                            (every? (fn [r] (and (not= :pending (:status r))
                                                 (<= (or (:quorum-at r) (:expired-at r) (:created r) 0)
                                                     (- now retention-ms))))
                                    runs))
                   sha)))
         sort vec)))

(defn queue-depth
  "Demand for runners of tier with no required toolchain: replica jobs of
  :pending runs that are :queued or whose lease expired (:queued), and those
  with a live lease (:leased). Small, so a scaler can poll it cheaply."
  [state now tier]
  (let [runs (:runs state)
        open (for [j (vals (:jobs state))
                   :let [r (get runs (:run-id j))]
                   :when (and (= :pending (:status r))
                              (= tier (get-in r [:pipeline :tier] :t2))
                              (nil? (get-in r [:pipeline :toolchain])))]
               (cond (= :queued (:status j)) :queued
                     (and (= :leased (:status j)) (> (:expires j 0) now)) :leased
                     (= :leased (:status j)) :queued
                     :else nil))
        f (frequencies (remove nil? open))]
    {:tier tier :queued (get f :queued 0) :leased (get f :leased 0)}))

(defn pending-actions [state] (outbox/pending (:outbox state)))

(defn mark-delivered [state k]
  (let [r (outbox/mark-delivered (:outbox state) k)]
    (if (:error r) state (assoc state :outbox (:outbox r)))))

(defn run-view [state run-id]
  (when-let [r (get-in state [:runs run-id])]
    (assoc (select-keys r [:repo :sha :ref :pipeline-cid :threshold :status :entries :outcome :created])
           :run-id run-id
           :replicas (->> (:jobs state) vals (filter #(= run-id (:run-id %)))
                          (map #(cond-> (select-keys % [:index :status :runner])
                                  (:receipt-cid %) (assoc :log (str "/v1/runs/" run-id "/log/" (:index %)))))
                          (sort-by :index) vec))))

(defn- elapsed-ms [start end]
  (when (and (integer? start) (integer? end) (<= start end))
    (- end start)))

(defn run-timings [state run-id read-object]
  (let [{:keys [created quorum-at]} (get-in state [:runs run-id])]
    {:quorum-ms (elapsed-ms created quorum-at)
     :replicas (->> (:jobs state)
                    (keep (fn [[job-id {:keys [index leased-at completed-at receipt-cid] :as job}]]
                            (when (= run-id (:run-id job))
                              (let [receipt (when receipt-cid (read-object receipt-cid))
                                    steps (:steps receipt)]
                                {:index index
                                 :queue-ms (elapsed-ms created leased-at)
                                 :lease-to-completion-ms (elapsed-ms leased-at completed-at)
                                 :steps (when (and (= job-id (:job-id receipt))
                                                   (vector? steps) (<= (count steps) 64)
                                                   (every? map? steps))
                                          (mapv (fn [index {:keys [ms exit]}]
                                                  {:index index
                                                   :ms (elapsed-ms 0 ms)
                                                   :exit (when (integer? exit) exit)})
                                                (range) steps))}))))
                    (sort-by :index)
                    vec)}))

(defn run-bundle
  "Everything tatara.core.verify/verify-run needs to re-check a run on a
  device: the request, threshold, allowlist, signed submissions, the artifact
  objects they name, and what this server claims. read-object: cid -> value
  or nil. Runs completed before submissions were kept have no entries and so
  do not verify."
  [{:keys [allow]} state run-id read-object]
  (when-let [r (get-in state [:runs run-id])]
    (let [subs (vec (:submissions r))
          cids (distinct (for [s subs a (get-in s [:verdict :artifacts])] (:cid a)))
          vcid (get-in r [:outcome :verdict-cid])]
      {:request (get-in state [:requests run-id])
       :threshold (:threshold r)
       :allow (set allow)
       :entries subs
       :objects (into {} (keep (fn [c] (when-some [v (read-object c)] [c v]))) cids)
       :claimed (cond-> {:status (:status r)} vcid (assoc :verdict-cid vcid))})))
