(ns tatara.coordinator.publish
  "Artifact publication to B2 (AWAI-3834). Runners never hold storage keys:
  they upload declared artifacts to this coordinator's CAS with their lease,
  and this worker copies the artifacts of a passed run to B2.

  A trusted pipeline opts in with
    :publish {:context \"tatara/apk\" :link \"<artifact name>\"}
  For each such run that reached quorum on :passed, the worker:
  1. uploads every artifact as <repo>/<sha>/<run-key>/<basename>, and a
     manifest.json listing path, size and sha256 of each;
  2. downloads each file back and compares size and sha256 (read-back);
  3. records the publication in the state and queues a commit status with a
     time-limited link to the :link artifact (or the manifest).
  A failure is retried on the next round; after :max-attempts the status is
  an error. Nothing is ever deleted from the bucket."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [tatara.coordinator.b2 :as b2]
            [tatara.coordinator.http :as srv]
            [tatara.coordinator.service :as svc]
            [tatara.coordinator.store :as store]))

(def default-context "tatara/apk")
(def default-max-attempts 3)

(defn- passed? [r]
  (and (= :reached (:status r))
       (let [vc (get-in r [:outcome :verdict-cid])]
         (= :passed (some #(when (= vc (:verdict-cid %)) (:result %)) (:entries r))))))

(defn due
  "Run ids whose pipeline asks for publication, that passed, and that are not
  yet published or given up on."
  [state]
  (->> (:runs state)
       (filter (fn [[id r]] (and (map? (get-in r [:pipeline :publish])) (passed? r)
                                 (not (#{:published :failed} (get-in state [:publications id :status]))))))
       (sort-by (fn [[id r]] [(:quorum-at r 0) id]))
       (mapv key)))

(defn artifacts
  "[{:name :sha256 :size :path}] of the run's agreed verdict, from the CAS."
  [root r]
  (let [verdict (store/read-object root (get-in r [:outcome :verdict-cid]))]
    (vec (for [{:keys [name cid]} (:artifacts verdict)
               :let [{sha :tatara/blob size :size} (store/read-object root cid)]]
           {:name name :sha256 sha :size size :path (str (store/blob-path root sha))}))))

(defn- content-type [n]
  (cond (str/ends-with? n ".apk") "application/vnd.android.package-archive"
        (str/ends-with? n ".json") "application/json"
        :else "application/octet-stream"))

(defn run-key
  "A short, path-safe name for a run id (\"cid:sha256:<hex>\" -> 16 hex)."
  [run-id]
  (let [h (last (str/split (str run-id) #":"))] (subs h 0 (min 16 (count h)))))

(defn object-prefix [r run-id]
  (str (:repo r) "/" (:sha r) "/" (run-key run-id) "/"))

(defn publish-run!
  "Upload, read back and link one run. Returns the publication record."
  [{:keys [root]} b2cfg session-fn run-id r now]
  (let [arts (artifacts root r)
        prefix (object-prefix r run-id)
        session (session-fn)
        files (vec (for [{:keys [name sha256 size path]} arts
                         :let [data (fs/read-all-bytes path)
                               object (str prefix (fs/file-name name))]]
                     (do (when-not (and (= size (count data)) (= sha256 (b2/sha256-hex data)))
                           (throw (ex-info "CAS blob does not match its verdict" {:artifact name})))
                         (let [u (b2/upload! session object data (content-type name))]
                           {:artifact name :object object :size size :sha256 sha256 :b2-file-id (:file-id u)}))))
        _ (when (empty? files) (throw (ex-info "passed run has no artifacts to publish" {:run-id run-id})))
        manifest {:format "tatara-artifacts/v1" :repo (:repo r) :sha (:sha r) :ref (:ref r)
                  :run-id run-id :pipeline-cid (:pipeline-cid r)
                  :verdict-cid (get-in r [:outcome :verdict-cid])
                  :runners (vec (sort (map :runner (:entries r))))
                  :signing "debug keystore only; no signing key on CI"
                  :published-at (str (java.time.Instant/ofEpochMilli now))
                  :files (mapv #(select-keys % [:artifact :object :size :sha256]) files)}
        mbytes (.getBytes (json/generate-string manifest {:pretty true}) "UTF-8")
        mobject (str prefix "manifest.json")
        mu (b2/upload! session mobject mbytes "application/json")
        ;; Read-back: every file and the manifest, by size and sha256.
        checks (for [{:keys [object size sha256]} (conj files {:object mobject :size (count mbytes)
                                                               :sha256 (b2/sha256-hex mbytes)})]
                 (let [back (b2/download session object)]
                   {:object object :ok (and (= size (count back)) (= sha256 (b2/sha256-hex back)))}))
        bad (remove :ok checks)]
    (when (seq bad) (throw (ex-info "read-back mismatch" {:objects (mapv :object bad)})))
    (let [link-art (get-in r [:pipeline :publish :link])
          target (or (some #(when (= link-art (:artifact %)) (:object %)) files) mobject)]
      {:status :published :at now :manifest mobject :manifest-sha256 (:sha256 mu)
       :files (mapv #(select-keys % [:artifact :object :size :sha256]) files)
       :read-back (count checks)
       :link (b2/link session target (:link-ttl-s b2cfg 604800))
       :link-object target})))

(defn- status-action [run-id r state description url]
  (cond-> {:kind :github-status :run-id run-id :repo (:repo r) :sha (:sha r) :state state
           :context (get-in r [:pipeline :publish :context] default-context) :description description}
    url (assoc :target-url url)))

(defn- record! [server run-id pub action]
  (srv/mutate-state!
   server (fn [s] (cond-> (assoc-in s [:publications run-id] pub)
                 action (svc/enqueue-action action)))))

(defn publish-once
  "Publish every due run. Returns [{:run-id :status ...}] for logging; never
  contains keys or links."
  [server b2cfg session-fn]
  (let [now ((:clock server))
        max-attempts (:max-attempts b2cfg default-max-attempts)]
    (vec
     (for [id (due @(:state server))
           :let [r (get-in @(:state server) [:runs id])]]
       (try
         (let [pub (publish-run! server b2cfg session-fn id r now)
               apk (some #(when (= (:link-object pub) (:object %)) %) (:files pub))
               desc (str "Published to B2, read back OK"
                         (when apk (str "; sha256 " (subs (:sha256 apk) 0 12) ", " (:size apk) " bytes")))]
           (record! server id pub (status-action id r "success" desc (:link pub)))
           {:run-id id :status :published :files (count (:files pub))})
         (catch Exception e
           (let [n (inc (get-in @(:state server) [:publications id :attempts] 0))
                 give-up? (>= n max-attempts)
                 err (merge {:message (ex-message e)} (select-keys (ex-data e) [:status :code :objects :artifact]))]
             (record! server id {:status (if give-up? :failed :retry) :attempts n :error err :at now}
                      (when give-up? (status-action id r "error" (str "Publish to B2 failed: " (ex-message e)) nil)))
             {:run-id id :status (if give-up? :failed :retry) :error err})))))))

(defn session-provider
  "Authorize lazily and again before the B2 token's 24 h lifetime runs out."
  [b2cfg]
  (let [cache (atom nil)]
    (fn []
      (let [now (System/currentTimeMillis)]
        (if-let [{:keys [s at]} @cache]
          (if (< (- now at) (* 20 3600 1000)) s (:s (reset! cache {:s (b2/authorize b2cfg) :at now})))
          (:s (reset! cache {:s (b2/authorize b2cfg) :at now})))))))
