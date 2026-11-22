(ns tatara.runner.cache
  "Build caches for heavy jobs (AWAI-3834), kept by the runner, not by steps.

  A trusted pipeline (the default branch's) names its caches:
    :cache [{:name \"cargo\" :env \"CARGO_HOME\" :key [\"app/src-tauri/Cargo.lock\"]}]
  The key is the sha256 of the name, the runner's :cache :salt (the toolchain
  id) and the key files' contents. Before the steps, the runner copies the
  entry for that key (or, failing that, the newest entry of the same name) from
  :cache :root into the job directory and points the variable at the copy.
  Steps (uid 20000) can read the cache root but never write it.

  Write rule (phase 0): only a passed run of a default branch head
  (refs/heads/...) writes an entry, and only when its exact key is missing.
  PR heads read only. Entries are written to a temporary name and renamed, so
  a half-written entry is never read. At most :keep entries per name are kept."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [tatara.coordinator.crypto :as crypto]))

(defn entry-key
  "Cache key of entry e for the job's source directory src."
  [{:keys [salt]} {:keys [name key]} src]
  (crypto/sha256-hex
   (str/join "\n" (concat [name (or salt "")]
                          (for [k key :let [f (fs/file src k)]]
                            (str k " " (if (fs/regular-file? f)
                                         (crypto/sha256-bytes (fs/read-all-bytes f))
                                         "absent")))))))

(defn writes?
  "Only a passed default-branch head writes (PR heads read only)."
  [job passed?]
  (boolean (and passed? (some->> (:ref job) (re-find #"^refs/heads/")))))

(defn- entries-of [root name]
  (let [d (fs/path root name)]
    (if (fs/directory? d)
      (->> (fs/list-dir d)
           (filter #(and (fs/directory? %) (re-matches #"[0-9a-f]{64}" (str (fs/file-name %)))))
           (sort-by #(- (.toMillis (fs/last-modified-time %)))))
      [])))

(defn- copy-tree! [from to]
  (fs/create-dirs to)
  (let [r (p/shell {:out :string :err :string :continue true}
                   "cp" "-a" "--reflink=auto" (str from "/.") (str to))]
    (when-not (zero? (:exit r)) (throw (ex-info "cache copy failed" {:from (str from) :err (:err r)})))))

(defn restore!
  "Copy each entry's cache into src/.tatara-cache/<name>. Returns
  {:env {VAR path} :report {name {:key :hit :exact|:fallback|:miss :ms}}}."
  [{:keys [cache]} pipeline src]
  (reduce
   (fn [acc {:keys [name env] :as e}]
     (let [t0 (System/currentTimeMillis)
           k (entry-key cache e src)
           exact (fs/path (:root cache) name k)
           from (if (fs/directory? exact) exact (first (entries-of (:root cache) name)))
           to (fs/path src ".tatara-cache" name)]
       (if from (copy-tree! from to) (fs/create-dirs to))
       (-> acc
           (assoc-in [:env env] (str to))
           (assoc-in [:report name] {:key k :hit (cond (= from exact) :exact from :fallback :else :miss)
                                     :ms (- (System/currentTimeMillis) t0)}))))
   {:env {} :report {}}
   (:cache pipeline)))

(defn save!
  "After the steps: write missing exact entries when (writes? job passed?).
  Returns the report with :saved true on written entries."
  [{:keys [cache step-user]} job pipeline src report passed?]
  (if-not (writes? job passed?)
    report
    (reduce
     (fn [rep {:keys [name]}]
       (let [{:keys [key hit]} (get rep name)
             from (fs/path src ".tatara-cache" name)
             dest (fs/path (:root cache) name key)]
         (if (or (= :exact hit) (fs/exists? dest) (not (fs/directory? from)))
           rep
           (let [tmp (fs/path (:root cache) name (str ".tmp-" key "-" (System/nanoTime)))]
             (try
               (copy-tree! from tmp)
               ;; Steps ran as the step user and own the copy; the entry belongs to the runner.
               (when step-user (p/shell "chown" "-R" "0:0" (str tmp)))
               (p/shell "chmod" "-R" "go-w,a+rX" (str tmp))
               (fs/move tmp dest {:atomic-move true})
               (doseq [old (drop (:keep cache 2) (entries-of (:root cache) name))]
                 (fs/delete-tree old))
               (assoc-in rep [name :saved] true)
               (catch Exception e
                 (fs/delete-tree tmp)
                 (assoc-in rep [name :save-error] (ex-message e))))))))
     report
     (:cache pipeline))))
