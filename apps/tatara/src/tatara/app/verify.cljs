(ns tatara.app.verify
  "Device-side check of a run bundle with tatara-core's verify-run (AWAI-3410).
  The bundle comes from the coordinator's GET /v1/runs/<id>/bundle; nothing in
  it is trusted: CIDs, signatures and the quorum are recomputed here.
  js/tataraCrypto (src/crypto.js) supplies SHA-256 and Ed25519."
  (:require [clojure.edn :as edn]
            [tatara.core.canonical :as c]
            [tatara.core.verify :as vf]))

(defn ctx []
  (let [cr (.-tataraCrypto js/globalThis)]
    {:hash (fn [s] (.sha256Hex cr s))
     :verify (fn [{:keys [runner signed signature]}]
               (boolean (.verifyDid cr runner (c/canonical signed) signature)))}))

(defn read-bundle [text] (edn/read-string text))

(defn verify-bundle [bundle] (vf/verify-run (ctx) bundle))

(defn- flip [s] (str (if (= "x" (subs s 0 1)) "y" "x") (subs s 1)))

(def tampers
  "Named one-field changes; each must make a valid bundle fail."
  {"署名" #(update-in % [:entries 1 :signature] flip)
   "verdict の結果" #(assoc-in % [:entries 0 :verdict :result] :failed)
   "commit" #(update-in % [:request :sha] flip)
   "artifact" (fn [b] (let [k (first (keys (:objects b)))] (update-in b [:objects k :size] inc)))
   "server の主張" #(assoc-in % [:claimed :status] :pending)
   "allowlist" #(update % :allow disj (get-in % [:entries 1 :runner]))})

(defn summary
  "Plain data for the screen."
  [{:keys [ok? quorum failed]}]
  {:ok? ok? :quorum (name (:status quorum))
   :failed (mapv (fn [f] (pr-str (:check f))) failed)})
