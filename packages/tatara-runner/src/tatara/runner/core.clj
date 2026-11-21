(ns tatara.runner.core
  "Fleet runner for tier T2 (Linux VM). One job at a time:
  lease -> download the source snapshot from the coordinator -> check that the
  leased pipeline hashes to its CID -> run argv steps ->
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
  "T2 job rules: tier, argv-only steps, network :none or :fetch. :fetch runs
  only on a runner configured with a :fetch egress proxy (allowlist); without
  one the job is refused instead of getting the host's network (AWAI-3799)."
  [{:keys [pipeline pipeline-cid]} & [cfg]]
  (let [steps (:steps pipeline)]
    (cond
      (not= pipeline-cid (c/cid crypto/ctx pipeline)) {:error :pipeline-cid-mismatch}
      (not= :t2 (:tier pipeline :t2)) {:error :wrong-tier}
      (or (empty? steps) (> (count steps) max-steps)) {:error :bad-step-count}
      (not (every? #(and (vector? %) (seq %) (every? string? %)) steps)) {:error :not-argv}
      (not (contains? #{:none :fetch} (:network pipeline :none))) {:error :network-not-allowed}
      (and (= :fetch (:network pipeline)) (not (get-in cfg [:fetch :proxy]))) {:error :fetch-not-allowed}
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

(defn drop-privileges
  "With :step-user {:uid :gid}, the step runs under that uid with no
  capabilities and no_new_privs, so it cannot read the runner's identity
  (mode 0600, owned by the runner) or regain privilege (AWAI-3799)."
  [argv step-user]
  (if-let [{:keys [uid gid]} step-user]
    (into ["setpriv" (str "--reuid=" uid) (str "--regid=" (or gid uid)) "--clear-groups"
           "--inh-caps=-all" "--no-new-privs" "--"] argv)
    argv))

(defn wrap-network
  "A :network :none step runs in a fresh network namespace. Only loopback is
  brought up inside it, so steps can still use localhost (test servers).
  With a :step-user (Kubernetes pods, AWAI-3799) the runner first drops to
  that uid, then the step unshares its own user and network namespace (no
  NET_ADMIN or SYS_ADMIN needed in the pod), brings up loopback there and
  clears its bounding set before exec, so it runs with no capabilities. Without
  one (VM runners) the unshare maps the invoking user to root, as before."
  ([argv network enforcement] (wrap-network argv network enforcement nil))
  ([argv network enforcement step-user]
   (let [lo ["sh" "-c" "ip link set lo up && exec \"$@\"" "tatara-step"]]
     (cond
       (not (and (= :none network) (= :unshare enforcement))) (drop-privileges argv step-user)
       step-user (drop-privileges
                  (into ["unshare" "--user" "--map-root-user" "--net" "--"
                         "sh" "-c" "ip link set lo up && exec setpriv --bounding-set=-all --inh-caps=-all -- \"$@\""
                         "tatara-step"] argv)
                  step-user)
       :else (into (into ["unshare" "--net" "--map-root-user" "--"] lo) argv)))))

(defn fetch-env
  "Environment for a :fetch step: all HTTP(S) through the egress proxy."
  [{:keys [fetch]} network]
  (if (and (= :fetch network) (:proxy fetch))
    (let [px (:proxy fetch)]
      {"HTTP_PROXY" px "HTTPS_PROXY" px "http_proxy" px "https_proxy" px
       "NO_PROXY" "" "no_proxy" "" "npm_config_proxy" px "npm_config_https_proxy" px})
    {}))

(defn run-steps
  "Run steps in order in dir; stop at the first non-zero exit or the deadline.
  Returns {:steps [{:argv :exit :ms}] :failed? bool}."
  [{:keys [network-enforcement env step-user] :as cfg} pipeline dir log-file]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 (:timeout-seconds pipeline 3600)))
        home (str (fs/create-dirs (fs/path dir ".tatara-home")))
        tmp (str (fs/create-dirs (fs/path dir ".tatara-tmp")))
        base-env (merge {"PATH" (System/getenv "PATH") "HOME" home "TMPDIR" tmp "LANG" "C.UTF-8"} env
                        (fetch-env cfg (:network pipeline :none)))]
    (when-let [{:keys [uid gid]} step-user]
      (p/shell "chown" "-R" (str uid ":" (or gid uid)) (str dir)))
    (loop [[argv & more] (:steps pipeline) done []]
      (if-not argv
        {:steps done :failed? false}
        (let [t0 (System/currentTimeMillis)
              full (wrap-network argv (:network pipeline :none) network-enforcement step-user)
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
  (let [chk (validate job cfg)]
    (if (:error chk)
      {:refused (:error chk)}
      (let [dir (str (fs/create-dirs (fs/path work (str "job-" (subs (crypto/sha256-hex (:job-id job)) 0 16)))))
            snap (:snapshot job)
            blob (when snap (cl/get-blob c job (:sha256 snap)))]
        (cond
          (nil? snap) {:refused :no-snapshot}
          (not (:bytes blob)) {:refused :snapshot-unavailable :detail (dissoc blob :bytes)}
          :else
          ;; The leased pipeline is the trusted one (the default branch's, AWAI-3799);
          ;; validate checked it against its CID. A PR's own .tatara/pipeline.edn is
          ;; not used, so it may differ from the checkout.
          (let [src (extract! (:bytes blob) dir)]
            (do
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
