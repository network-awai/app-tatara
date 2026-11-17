(ns tatara.core.verdict
  "Verdict CID, completion admission and quorum."
  (:require [tatara.core.canonical :as c]
            [tatara.core.lease :as lease]))

(defn verdict-cid
  "Only source, pipeline, result and artifact identities; no timing or logs."
  [ctx verdict]
  (c/cid ctx (select-keys verdict [:source :pipeline-cid :result :artifacts])))

(defn admit-completion
  "All checks must hold. Returns {:ok submission-summary} or {:error kw}.
  ctx: {:hash f, :allow #{runner-did}, :cas-has? (fn [cid] bool),
        :verify (fn [submission] bool)}  ; :verify is the Identity port.
  expected: {:rid :ref}."
  [{:keys [allow cas-has? verify] :as ctx} state expected sub]
  (let [l (lease/check ctx state (select-keys sub [:job-id :runner :token :now]))
        vcid (verdict-cid ctx (:verdict sub))
        signed (:signed sub)
        needed (concat [(:verdict-cid signed) (:receipt-cid sub) (:snapshot-cid sub)]
                       (map :cid (get-in sub [:verdict :artifacts])))]
    (cond
      (:error l) l
      (not (contains? allow (:runner sub))) {:error :runner-not-allowed}
      (not (verify sub)) {:error :bad-signature}
      (not= (:rid expected) (:rid signed)) {:error :wrong-rid}
      (not= (:ref expected) (:ref signed)) {:error :wrong-ref}
      (not= vcid (:verdict-cid signed)) {:error :verdict-cid-mismatch}
      (not (every? cas-has? needed)) {:error :cas-missing
                                      :missing (vec (remove cas-has? needed))}
      :else {:ok {:runner (:runner sub) :verdict-cid vcid}})))

(defn quorum
  "entries: [{:runner did :verdict-cid cid}] of admitted completions.
  Only distinct DIDs count. The same DID with two verdicts, or competing
  verdicts once the distinct-DID count reaches threshold, is a hard error.
  Arrival order never decides."
  [threshold entries]
  (let [by-runner (group-by :runner entries)
        equivocating (filter #(> (count (distinct (map :verdict-cid (val %)))) 1) by-runner)]
    (if (seq equivocating)
      {:status :hard-error :reason :runner-equivocation :runners (vec (map key equivocating))}
      (let [per (into {} (map (fn [[r es]] [r (:verdict-cid (first es))])) by-runner)
            cids (distinct (vals per))]
        (cond
          (and (> (count cids) 1) (>= (count per) threshold))
          {:status :hard-error :reason :split-threshold :verdicts (vec (sort cids))}
          (and (= 1 (count cids)) (>= (count per) threshold))
          {:status :reached :verdict-cid (first cids) :runners (vec (sort (keys per)))}
          :else {:status :pending :have (count per) :need threshold})))))
