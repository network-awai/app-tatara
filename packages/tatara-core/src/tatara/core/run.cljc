(ns tatara.core.run
  "RunRequest normalization and duplicate-event idempotency."
  (:require [tatara.core.canonical :as c]))

(def ^:private required [:forge :repo :ref :sha :pipeline-cid])

(defn normalize
  "Authenticated forge event -> immutable logical RunRequest.
  The delivery id is excluded, so redeliveries of one event get one :run/id."
  [ctx event]
  (let [missing (remove #(string? (get event %)) required)]
    (if (seq missing)
      {:error :invalid-event :missing (vec missing)}
      (let [req (select-keys event required)]
        {:ok (assoc req :run/id (c/cid ctx req))}))))

(defn admit
  "Idempotently add a request to state {:runs {run-id request}}.
  Returns {:state s :duplicate? bool :run-id id}."
  [state request]
  (let [id (:run/id request)]
    (if (contains? (:runs state) id)
      {:state state :duplicate? true :run-id id}
      {:state (assoc-in state [:runs id] request) :duplicate? false :run-id id})))
