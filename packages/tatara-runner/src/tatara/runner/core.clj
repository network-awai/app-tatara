(ns tatara.runner.core
  "Fleet runner for tier T2 (Linux VM). One job at a time:
  lease -> download the source snapshot from the coordinator -> check that the
  checked-out pipeline hashes to the leased pipeline CID -> run argv steps ->
  hash declared artifacts on the host -> upload -> sign and complete.

  The VM is the isolation boundary. :network :none steps are wrapped in a new
  network namespace when :network-enforcement is :unshare (Linux); the
  enforcement actually used is written into the receipt. The verdict holds only
  source, pipeline, result and artifact identities, never timing or logs."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [tatara.core.canonical :as c]
            [tatara.coordinator.client :as cl]
            [tatara.coordinator.crypto :as crypto]))

(def max-steps 64)

(defn validate
  "T2 job rules: tier, argv-only steps, network :none or :fetch."
  [{:keys [pipeline pipeline-cid]}]
  (let [steps (:steps pipeline)]
    (cond
      (not= pipeline-cid (c/cid crypto/ctx pipeline)) {:error :pipeline-cid-mismatch}
      (not= :t2 (:tier pipeline :t2)) {:error :wrong-tier}
      (or (empty? steps) (> (count steps) max-steps)) {:error :bad-step-count}
      (not (every? #(and (vector? %) (seq %) (every? string? %)) steps)) {:error :not-argv}
      (not (contains? #{:none :fetch} (:network pipeline :none))) {:error :network-not-allowed}
      (not (every? #(and (string? %) (not (fs/absolute? %)) (not (re-find #"(^|/)\.\.(/|$)" %)))
                   (:artifacts pipeline))) {:error :bad-artifact-path}
      :else {:ok true})))

(defn- extract! [^bytes tarball dir]
  (let [f (fs/file dir "snapshot.tar.gz") src (fs/file dir "src")]
    (fs/create-dirs src)
    (with-open [o (io/output-stream f)] (.write o tarball))
    (let [r (p/shell {:out :string :err :string :continue true}
                     "tar" "-xzf" (str f) "-C" (str src) "--strip-components=1")]
      (when-not (zero? (:exit r)) (throw (ex-info "snapshot extraction failed" {:err (:err r)}))))
    (str src)))

(defn- wrap-network [argv network enforcement]
  (if (and (= :none network) (= :unshare enforcement))
    (into ["unshare" "--net" "--map-root-user" "--"] argv)
    argv))

(defn run-steps
  "Run steps in order in dir; stop at the first non-zero exit or the deadline.
  Returns {:steps [{:argv :exit :ms}] :failed? bool}."
  [{:keys [network-enforcement env]} pipeline dir log-file]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 (:timeout-seconds pipeline 3600)))
        home (str (fs/create-dirs (fs/path dir ".tatara-home")))
        tmp (str (fs/create-dirs (fs/path dir ".tatara-tmp")))
        base-env (merge {"PATH" (System/getenv "PATH") "HOME" home "TMPDIR" tmp "LANG" "C.UTF-8"} env)]
    (loop [[argv & more] (:steps pipeline) done []]
      (if-not argv
        {:steps done :failed? false}
        (let [t0 (System/currentTimeMillis)
              full (wrap-network argv (:network pipeline :none) network-enforcement)
              proc (apply p/process {:dir dir :env base-env
                                     :out :append :out-file (fs/file log-file) :err :out} full)
              left (- deadline t0)
              finished? (and (pos? left) (deref (future @proc) left nil))
              exit (if finished? (:exit @proc) (do (p/destroy-tree proc) -1))
              step {:argv argv :exit exit :ms (- (System/currentTimeMillis) t0)}]
          (if (zero? exit)
            (recur more (conj done step))
            {:steps (conj done step) :failed? true}))))))

(defn- upload-object [c job v]
  (let [r (cl/put-object c job v)]
    (or (get-in r [:body :cid]) (throw (ex-info "object upload failed" {:reply r})))))

(defn- upload-file-artifact [c job f]
  (let [data (fs/read-all-bytes f)
        b (cl/put-blob c job data)]
    (when-not (= 200 (:status b)) (throw (ex-info "blob upload failed" {:reply b})))
    (upload-object c job {:tatara/blob (:sha b) :size (count data)})))

(defn process-job
  "Execute a leased job and submit the verdict. Returns the coordinator reply,
  or {:refused reason} when the job is not run."
  [{:keys [work] :as cfg} c job]
  (let [chk (validate job)]
    (if (:error chk)
      {:refused (:error chk)}
      (let [dir (str (fs/create-dirs (fs/path work (str "job-" (subs (crypto/sha256-hex (:job-id job)) 0 16)))))
            snap (:snapshot job)
            blob (when snap (cl/get-blob c job (:sha256 snap)))]
        (cond
          (nil? snap) {:refused :no-snapshot}
          (not (:bytes blob)) {:refused :snapshot-unavailable :detail (dissoc blob :bytes)}
          :else
          (let [src (extract! (:bytes blob) dir)
                pfile (fs/file src ".tatara" "pipeline.edn")
                checked (when (fs/exists? pfile) (edn/read-string (slurp pfile)))]
            (if (not= (:pipeline-cid job) (some->> checked (c/cid crypto/ctx)))
              {:refused :checkout-pipeline-mismatch}
              (let [log (fs/file dir "log.txt")
                    hb (future (loop [] (Thread/sleep (long (max 1000 (quot (:ttl-ms job 120000) 3))))
                                 (cl/heartbeat c job) (recur)))
                    {:keys [steps failed?]} (try (run-steps cfg (:pipeline job) src log)
                                                 (finally (future-cancel hb)))
                    names (sort (:artifacts (:pipeline job)))
                    present (filter #(fs/regular-file? (fs/file src %)) names)
                    passed? (and (not failed?) (= (count present) (count names)))
                    artifacts (if passed?
                                (mapv (fn [n] {:name n :cid (upload-file-artifact c job (fs/file src n))}) names)
                                [])
                    verdict {:source (:sha job) :pipeline-cid (:pipeline-cid job)
                             :result (if passed? :passed :failed) :artifacts artifacts}
                    vcid (upload-object c job verdict)
                    log-blob (cl/put-blob c job (fs/read-all-bytes log))
                    receipt (upload-object c job {:job-id (:job-id job) :steps steps
                                                  :log-sha256 (:sha log-blob)
                                                  :network (:network (:pipeline job) :none)
                                                  :network-enforcement (:network-enforcement cfg)})
                    snapshot (upload-object c job {:sha (:sha job) :tarball-sha256 (:sha256 snap)})]
                (when-not (:keep-work? cfg) (fs/delete-tree dir))
                (:body (cl/complete c job verdict vcid receipt snapshot))))))))))

(defn run-once
  "Lease one job and process it. Returns :idle, or the processing result."
  [cfg c]
  (let [l (cl/lease c (:tiers cfg [:t2]))]
    (cond
      (= :no-job (get-in l [:body :error])) :idle
      (not= 200 (:status l)) {:lease-error l}
      :else (process-job cfg c (:body l)))))
