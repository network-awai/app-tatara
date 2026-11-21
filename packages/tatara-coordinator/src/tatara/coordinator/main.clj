(ns tatara.coordinator.main
  "bb serve CONFIG.edn
  CONFIG: {:root \"/var/lib/tatara\" :port 8790 :bind \"100.x.y.z\"
           :allow #{\"did:tatara:ed25519:...\"} :default-threshold 2
           :ttl-ms 120000 :admin-token-env \"TATARA_ADMIN_TOKEN\"
           :github {:repos [\"network-awai/network-awai-apex\"] :interval-ms 60000
                    :token-env \"TATARA_GITHUB_TOKEN\" :api \"https://api.github.com\"
                    :target-base \"https://tatara.example\"}}
  :github is optional. With :lease {:url \"http://MINTER:8795\" :identity \"lease-identity.edn\"
  :owner \"network-awai\" :repositories [...] :permissions {:contents \"read\" :statuses \"write\" :metadata \"read\" :pull_requests \"read\"}}
  the coordinator leases short tokens from kagitaba-minter instead of holding one.
  Optional merge gate (AWAI-3574): :github {:merge {:repos [\"network-awai/network-awai-apex\"]
  :label \"tatara:merge\" :method \"squash\" :lease {... :repositories [...] :permissions
  {:contents \"write\" :pull_requests \"read\" :metadata \"read\"}}}} squash-merges labelled PRs
  whose exact head passed this coordinator's quorum. Without :merge nothing is merged."
  (:require [clojure.edn :as edn]
            [tatara.coordinator.github :as github]
            [tatara.coordinator.http :as http]
            [tatara.coordinator.lease :as lease]))

(defn -main [& [config-path]]
  (when-not config-path
    (binding [*out* *err*] (println "usage: bb serve CONFIG.edn")) (System/exit 64))
  (let [cfg (edn/read-string (slurp config-path))
        admin (some-> (:admin-token-env cfg) System/getenv)
        srv (http/start! (dissoc cfg :admin-token-env :github :run-ttl-ms :snapshot-retention-ms) {:admin-token admin})
        gh (when-let [g (:github cfg)]
             (let [g (merge {:api "https://api.github.com"} g)]
               (if-let [l (:lease g)]
                 ;; Preferred: no long-lived token in this process (AWAI-3459).
                 (assoc g :token-fn (lease/token-provider (assoc l :identity (lease/read-identity (:identity l)))))
                 (let [token (some-> (:token-env g) System/getenv)]
                   (when-not token
                     (binding [*out* *err*] (println "github: token env" (:token-env g) "is empty")) (System/exit 78))
                   (assoc g :token token)))))
        ;; The merge gate gets its own lease (contents: write, pull_requests: read)
        ;; scoped to its repositories, so the polling lease stays read-only (AWAI-3574).
        gh (if-let [ml (get-in gh [:merge :lease])]
             (assoc-in gh [:merge :token-fn] (lease/token-provider (assoc ml :identity (lease/read-identity (:identity ml)))))
             gh)]
    (when gh (github/start-loops! srv gh) (println "github: polling" (count (:repos gh)) "repos"))
    ;; Close runs that stayed :pending past :run-ttl-ms (default 6 h), so lost
    ;; leases neither block a commit's status nor keep runner nodes up (AWAI-3799).
    ;; Source snapshots of closed runs are deleted after :snapshot-retention-ms
    ;; (default 12 h); they can be fetched from GitHub again.
    (let [ttl (long (:run-ttl-ms cfg (* 6 3600 1000)))
          keep-ms (long (:snapshot-retention-ms cfg (* 12 3600 1000)))]
      (doto (Thread. ^Runnable (fn [] (loop []
                                        (try (let [{:keys [expired]} (http/expire-stale! srv ttl)]
                                               (when (seq expired) (println "expired" (count expired) "stale runs" (pr-str expired))))
                                             (catch Exception e (println "expiry failed" (ex-message e))))
                                        (try (let [n (http/gc-snapshots! srv keep-ms)]
                                               (when (pos? n) (println "snapshot gc removed" n "blobs")))
                                             (catch Exception e (println "snapshot gc failed" (ex-message e))))
                                        (Thread/sleep 60000) (recur)))
                     "tatara-expiry")
        (.setDaemon true) (.start)))
    (println "tatara-coordinator listening on" (or (:bind cfg) "127.0.0.1") (:port srv)
             "runners:" (count (:allow cfg)))
    @(promise)))
