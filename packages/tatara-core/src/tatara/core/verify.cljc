(ns tatara.core.verify
  "Read-only, device-side verification of a fetched run bundle (P1).
  Nothing here fetches or trusts the server's claims: every CID is recomputed,
  the quorum is recomputed from the signed entries, and the result is compared
  with what the server claimed.

  bundle: {:request RunRequest-with-:run/id
           :threshold n
           :allow #{runner-did}
           :entries [{:runner did :verdict v :signed {:rid :ref :verdict-cid}
                      :signature s}]
           :objects {cid data}          ; artifact/receipt objects shipped along
           :claimed {:status :reached|:pending|:hard-error :verdict-cid cid}}
  ctx: {:hash f :verify (fn [entry] bool)}  ; :verify is the Identity port."
  (:require [tatara.core.canonical :as c]
            [tatara.core.verdict :as v]))

(defn- check [name ok? & [detail]]
  (cond-> {:check name :ok? (boolean ok?)} detail (assoc :detail detail)))

(defn verify-run [{:keys [verify] :as ctx} {:keys [request threshold allow entries objects claimed]}]
  (let [req-ids (c/cid ctx (select-keys request [:forge :repo :ref :sha :pipeline-cid]))
        entry-checks
        (mapcat
         (fn [{:keys [runner verdict signed] :as e}]
           [(check [:verdict-cid runner] (= (v/verdict-cid ctx verdict) (:verdict-cid signed)))
            (check [:allowed runner] (contains? allow runner))
            (check [:signature runner] (verify e))])
         entries)
        object-checks
        (map (fn [[k data]] (check [:object k] (= k (c/cid ctx data)))) objects)
        every-artifact-shipped
        (for [e entries a (get-in e [:verdict :artifacts])]
          (check [:artifact-present (:cid a)] (contains? objects (:cid a))))
        recomputed (v/quorum threshold
                             (map (fn [e] {:runner (:runner e)
                                           :verdict-cid (v/verdict-cid ctx (:verdict e))})
                                  entries))
        quorum-check (check :quorum-matches-claim
                            (= (select-keys recomputed [:status :verdict-cid])
                               (select-keys claimed [:status :verdict-cid]))
                            {:recomputed recomputed})
        checks (vec (concat [(check :run-id-matches (= (:run/id request) req-ids))]
                            entry-checks object-checks every-artifact-shipped [quorum-check]))]
    {:ok? (every? :ok? checks)
     :quorum recomputed
     :failed (vec (remove :ok? checks))}))
