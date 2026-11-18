(ns tatara.coordinator.main
  "bb serve CONFIG.edn
  CONFIG: {:root \"/var/lib/tatara\" :port 8790 :bind \"100.x.y.z\"
           :allow #{\"did:tatara:ed25519:...\"} :default-threshold 2
           :ttl-ms 120000 :admin-token-env \"TATARA_ADMIN_TOKEN\"
           :github {:repos [\"network-awai/network-awai-apex\"] :interval-ms 60000
                    :token-env \"TATARA_GITHUB_TOKEN\" :api \"https://api.github.com\"
                    :target-base \"https://tatara.example\"}}
  :github is optional. The token stays in this process."
  (:require [clojure.edn :as edn]
            [tatara.coordinator.github :as github]
            [tatara.coordinator.http :as http]))

(defn -main [& [config-path]]
  (when-not config-path
    (binding [*out* *err*] (println "usage: bb serve CONFIG.edn")) (System/exit 64))
  (let [cfg (edn/read-string (slurp config-path))
        admin (some-> (:admin-token-env cfg) System/getenv)
        srv (http/start! (dissoc cfg :admin-token-env :github) {:admin-token admin})
        gh (when-let [g (:github cfg)]
             (let [token (some-> (:token-env g) System/getenv)]
               (when-not token
                 (binding [*out* *err*] (println "github: token env" (:token-env g) "is empty")) (System/exit 78))
               (assoc (merge {:api "https://api.github.com"} g) :token token)))]
    (when gh (github/start-loops! srv gh) (println "github: polling" (count (:repos gh)) "repos"))
    (println "tatara-coordinator listening on" (or (:bind cfg) "127.0.0.1") (:port srv)
             "runners:" (count (:allow cfg)))
    @(promise)))
