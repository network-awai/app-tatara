(ns tatara.core.runner
  "Attended runner flow (P2, sandbox tier T1, plan A). Pure orchestration over
  ports; no I/O of its own.
  ports: {:sandbox (fn [argv limits] {:exit int :outputs {name data}})
          :approve (fn [op] bool)   ; direct trusted user choice, bound to op
          :send    (fn [op payload])}
  job: {:source sha :pipeline {:steps [[argv...]] :artifacts [names]}
        :pipeline-cid cid :limits {:network :none ...}}"
  (:require [tatara.core.canonical :as c]
            [tatara.core.verdict :as v]))

(def max-steps 64)

(defn validate-job
  "Argv-only steps, no network, and the pipeline must hash to the pinned CID."
  [ctx {:keys [pipeline pipeline-cid limits]}]
  (let [steps (:steps pipeline)]
    (cond
      (not= pipeline-cid (c/cid ctx pipeline)) {:error :pipeline-cid-mismatch}
      (or (empty? steps) (> (count steps) max-steps)) {:error :bad-step-count}
      (not (every? #(and (vector? %) (seq %) (every? string? %)) steps)) {:error :not-argv}
      (not= :none (:network limits)) {:error :network-not-allowed}
      :else {:ok true})))

(defn execute
  "Run steps in order; stop at the first non-zero exit. Declared artifacts are
  read and hashed by the host. Returns the verdict (without timing/logs)."
  [ctx {:keys [source pipeline pipeline-cid limits]} sandbox]
  (let [{:keys [failed? outputs]}
        (reduce (fn [acc argv]
                  (if (:failed? acc)
                    (reduced acc)
                    (let [r (sandbox argv limits)]
                      (-> acc
                          (update :outputs merge (:outputs r))
                          (assoc :failed? (not (zero? (:exit r))))))))
                {:failed? false :outputs {}} (:steps pipeline))
        names (sort (:artifacts pipeline))
        missing? (some #(not (contains? outputs %)) names)
        result (if (or failed? missing?) :failed :passed)]
    {:source source :pipeline-cid pipeline-cid :result result
     :artifacts (if (= :passed result)
                  (mapv (fn [n] {:name n :cid (c/cid ctx (get outputs n))}) names)
                  [])}))

(defn run-job
  "Validate, execute, then ask for approval before the only external effect.
  Denial sends nothing. op names the service, operation and data categories."
  [ctx job {:keys [sandbox approve send]} op]
  (let [chk (validate-job ctx job)]
    (if (:error chk)
      chk
      (let [verdict (execute ctx job sandbox)
            vcid (v/verdict-cid ctx verdict)]
        (if (approve op)
          (do (send op {:verdict verdict :verdict-cid vcid})
              {:status :submitted :verdict verdict :verdict-cid vcid})
          {:status :denied :verdict verdict :verdict-cid vcid})))))
