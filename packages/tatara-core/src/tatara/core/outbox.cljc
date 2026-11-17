(ns tatara.core.outbox
  "Content-addressed outbox. State: {:actions {cid action} :delivered #{cid}}."
  (:require [tatara.core.canonical :as c]))

(defn enqueue
  "Idempotent: the same action gets the same key and is never re-opened."
  [ctx outbox action]
  (let [k (c/cid ctx action)]
    {:outbox (update outbox :actions #(if (contains? % k) % (assoc % k action))) :key k}))

(defn mark-delivered [outbox k]
  (if (contains? (:actions outbox) k)
    {:outbox (update outbox :delivered (fnil conj #{}) k)}
    {:error :unknown-action}))

(defn pending [outbox]
  (->> (:actions outbox)
       (remove (fn [[k _]] (contains? (:delivered outbox) k)))
       (sort-by key)
       (mapv (fn [[k a]] {:key k :action a}))))
