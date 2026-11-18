(ns tatara.app.ui
  "Screen for the fleet device (AWAI-3410). The host (Rust) owns the device
  key and all network reads; this page only shows data and runs verify-run."
  (:require [clojure.string :as str]
            [tatara.app.verify :as v]))

(defn $ [id] (js/document.getElementById id))
(defn invoke [cmd args] (.invoke (.-__TAURI_INTERNALS__ js/window) cmd (clj->js args)))
(defn short-did [d] (if (string? d) (str "…" (subs d (max 0 (- (count d) 10)))) "-"))

(defn show-result! [el {:keys [ok? quorum failed]}]
  (set! (.-className el) (str "result " (if ok? "ok" "bad")))
  (set! (.-textContent el)
        (if ok? (str "検証できた（quorum: " quorum "）")
            (str "検証に失敗（quorum: " quorum "）: " (str/join ", " failed)))))

(defn show-error! [el msg]
  (set! (.-className el) "result bad")
  (set! (.-textContent el) (str msg)))

(defn verify-text! [el text f]
  (try (let [b (f (v/read-bundle text))]
         (if (empty? (:entries b))
           (show-error! el "検証できない: この run には署名付きの提出が残っていない（coordinator の更新前に完了した run）")
           (show-result! el (v/summary (v/verify-bundle b)))))
       (catch :default e (show-error! el (str "読めない bundle: " (.-message e))))))

(def fixture (atom nil))

(defn load-fixture! []
  (-> (js/fetch "fixture/run-bundle.edn") (.then #(.text %)) (.then #(reset! fixture %))))

(defn run-item [run]
  (let [li (js/document.createElement "li")
        info (js/document.createElement "div")
        btn (js/document.createElement "button")
        out (js/document.createElement "div")]
    (set! (.-textContent info)
          (str (:repo run) " " (subs (str (:sha run)) 0 (min 12 (count (str (:sha run)))))
               " — " (name (or (:status run) :unknown))
               " — runner: " (str/join ", " (map (comp short-did :runner) (:replicas run)))))
    (set! (.-textContent btn) "端末で検証する")
    (set! (.-className out) "result")
    (.addEventListener btn "click"
                       #(-> (invoke "tatara_bundle" {:runId (:run-id run)})
                            (.then (fn [text] (verify-text! out text identity)))
                            (.catch (fn [e] (show-error! out e)))))
    (doto li (.append info btn out))))

(defn load-runs! []
  (let [ul ($ "runs") err ($ "runs-error")]
    (set! (.-textContent err) "")
    (-> (invoke "tatara_runs" {})
        (.then (fn [text]
                 (let [runs (reverse (v/read-bundle text))]
                   (set! (.-innerHTML ul) "")
                   (doseq [r (take 30 runs)] (.append ul (run-item r)))
                   (when (empty? runs) (set! (.-textContent err) "run はまだない")))))
        (.catch (fn [e] (show-error! err e))))))

(defn init! []
  (let [sel ($ "tamper")]
    (doseq [k (keys v/tampers)]
      (let [o (js/document.createElement "option")] (set! (.-value o) k) (set! (.-textContent o) k) (.append sel o))))
  (load-fixture!)
  (-> (invoke "tatara_identity" {}) (.then #(set! (.-textContent ($ "did")) (.-did %)))
      (.catch #(set! (.-textContent ($ "did")) (str "鍵を用意できない: " %))))
  (-> (invoke "tatara_config_get" {}) (.then #(set! (.-value ($ "url")) (or (.-coordinator %) ""))))
  (.addEventListener ($ "fixture-verify") "click" #(verify-text! ($ "fixture-result") @fixture identity))
  (.addEventListener ($ "fixture-tamper") "click"
                     #(verify-text! ($ "fixture-result") @fixture (get v/tampers (.-value ($ "tamper")))))
  (.addEventListener ($ "save-url") "click"
                     #(-> (invoke "tatara_config_set" {:coordinator (.-value ($ "url"))})
                          (.then (fn [_] (set! (.-textContent ($ "runs-error")) "保存した")))
                          (.catch (fn [e] (show-error! ($ "runs-error") e)))))
  (.addEventListener ($ "load-runs") "click" load-runs!))

(init!)
