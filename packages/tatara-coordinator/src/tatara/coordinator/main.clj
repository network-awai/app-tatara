(ns tatara.coordinator.main
  "bb serve CONFIG.edn
  CONFIG: {:root \"/var/lib/tatara\" :port 8790 :bind \"100.x.y.z\"
           :allow #{\"did:tatara:ed25519:...\"} :default-threshold 2
           :ttl-ms 120000 :admin-token-env \"TATARA_ADMIN_TOKEN\"}"
  (:require [clojure.edn :as edn]
            [tatara.coordinator.http :as http]))

(defn -main [& [config-path]]
  (when-not config-path
    (binding [*out* *err*] (println "usage: bb serve CONFIG.edn")) (System/exit 64))
  (let [cfg (edn/read-string (slurp config-path))
        admin (some-> (:admin-token-env cfg) System/getenv)
        srv (http/start! (dissoc cfg :admin-token-env) {:admin-token admin})]
    (println "tatara-coordinator listening on" (or (:bind cfg) "127.0.0.1") (:port srv)
             "runners:" (count (:allow cfg)))
    @(promise)))
