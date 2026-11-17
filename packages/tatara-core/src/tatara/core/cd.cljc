(ns tatara.core.cd
  "CD policy: ref allowlist, release-bundle selection, capability checks,
  rollout and rollback order. Signing and node execution are ports.")

(defn ref-allowed?
  "policy: {:repos {repo #{exact-ref}}}. Exact match only; no prefixes."
  [policy repo ref]
  (contains? (get-in policy [:repos repo] #{}) ref))

(defn release-bundle
  "A passed verdict must contain exactly one artifact of type :release-bundle."
  [verdict]
  (let [bs (filter #(= :release-bundle (:type %)) (:artifacts verdict))]
    (cond
      (not= :passed (:result verdict)) {:error :not-passed}
      (not= 1 (count bs)) {:error :bundle-count :count (count bs)}
      :else {:ok (first bs)})))

(defn eligible
  "Deploy gate: allowed exact ref and one release bundle."
  [policy {:keys [repo ref verdict]}]
  (if-not (ref-allowed? policy repo ref)
    {:error :ref-not-allowed}
    (release-bundle verdict)))

(defn capability-valid?
  "Destination-side check against the node's own issuer allowlist.
  cap: {:issuer :rid :env :verdict-cid :not-before :not-after :nonce ...}
  node: {:issuers #{did} :seen-nonces #{n} :env env}."
  [node cap now]
  (cond
    (not (contains? (:issuers node) (:issuer cap))) {:error :issuer-not-allowed}
    (not= (:env node) (:env cap)) {:error :wrong-env}
    (< now (:not-before cap)) {:error :not-yet-valid}
    (>= now (:not-after cap)) {:error :expired}
    (contains? (:seen-nonces node) (:nonce cap)) {:error :replayed-nonce}
    :else {:ok cap}))

(defn rollout-plan
  "Canary (first node) then batches of batch-size."
  [nodes batch-size]
  (when (and (seq nodes) (pos? batch-size))
    (into [[(first nodes)]] (partition-all batch-size (rest nodes)))))

(defn rollback-order
  "Touched nodes in reverse order of touching."
  [touched] (vec (reverse touched)))
