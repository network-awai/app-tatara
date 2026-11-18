(ns tatara.runner.main
  "bb run RUNNER.edn
  RUNNER: {:coordinator \"http://100.x.y.z:8790\" :identity \"/var/lib/tatara/runner.edn\"
           :work \"/var/lib/tatara/work\" :tiers [:t2] :poll-ms 10000
           :network-enforcement :unshare :env {}}"
  (:require [clojure.edn :as edn]
            [tatara.coordinator.client :as cl]
            [tatara.coordinator.crypto :as crypto]
            [tatara.runner.core :as core]))

(defn -main [& [config-path]]
  (when-not config-path
    (binding [*out* *err*] (println "usage: bb run RUNNER.edn")) (System/exit 64))
  (let [cfg (edn/read-string (slurp config-path))
        id (crypto/read-identity (:identity cfg))
        c (cl/client (:coordinator cfg) id)]
    (println "tatara-runner" (:did id) "->" (:coordinator cfg)
             "enforcement" (:network-enforcement cfg))
    (loop []
      (let [r (try (core/run-once cfg c) (catch Exception e {:exception (.getMessage e)}))]
        (when-not (= :idle r) (println (pr-str r)))
        (when (= :idle r) (Thread/sleep (long (:poll-ms cfg 10000))))
        (recur)))))
