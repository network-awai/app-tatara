(ns make-fixture
  "Write test/fixtures/run-bundle.edn from a real coordinator run (AWAI-3410).
  The coordinator (JVM-side, Babashka) is the oracle: two runners with fresh
  test identities complete one job each; the bundle it serves is what the
  device must verify. Run from apps/tatara:
    bb --classpath ../../packages/tatara-coordinator/src:../../packages/tatara-core/src test/make_fixture.clj"
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.edn :as edn]
            [tatara.core.canonical :as c]
            [tatara.coordinator.client :as cl]
            [tatara.coordinator.crypto :as crypto]
            [tatara.coordinator.http :as srv]))

(def a (crypto/generate))
(def b (crypto/generate))
(def admin "fixture-admin")
(def clock (constantly 1795000000000))
;; The artifact name exercises string escaping and non-ASCII text, so the
;; page's canonical form must match the coordinator's byte for byte.
(def artifact-name "成果物 \"out\"\n\ttab\\")
(def pipeline {:tier :t2 :network :none :steps [["bb" "ci"]] :artifacts [artifact-name]})
(def event {:forge "github" :repo "network-awai/app-tatara" :ref "refs/heads/main"
            :sha "e00f00480587363beb8b684a4eed82de8900750d" :pipeline-cid (c/cid crypto/ctx pipeline)})

(defn job! [cli job]
  (let [blob (cl/put-blob cli job (.getBytes "fixture artifact bytes" "UTF-8"))
        art (cl/put-object cli job {:tatara/blob (:sha blob) :size 22})
        verdict {:source (:sha job) :pipeline-cid (:pipeline-cid job) :result :passed
                 :artifacts [{:name artifact-name :cid (get-in art [:body :cid])}]}
        vobj (cl/put-object cli job verdict)
        receipt (cl/put-object cli job {:job-id (:job-id job) :steps [{:argv ["bb" "ci"] :exit 0}]})
        snap (cl/put-object cli job {:sha (:sha job)})]
    (cl/complete cli job verdict (get-in vobj [:body :cid]) (get-in receipt [:body :cid]) (get-in snap [:body :cid]))))

(let [root (str (fs/create-temp-dir {:prefix "tatara-fixture-"}))
      s (srv/start! {:root root :allow #{(:did a) (:did b)} :ttl-ms 60000} {:clock clock :admin-token admin})
      base (str "http://127.0.0.1:" (:port s))]
  (try
    (let [run (edn/read-string (:body (http/post (str base "/v1/runs") {:headers {"authorization" (str "Bearer " admin)}
                                                                          :body (pr-str {:event event :pipeline pipeline})})))
          ca (cl/client base a clock) cb (cl/client base b clock)]
      (job! ca (:body (cl/lease ca [:t2])))
      (job! cb (:body (cl/lease cb [:t2])))
      (let [text (:body (http/get (str base "/v1/runs/" (:run-id run) "/bundle")))]
        (assert (= :reached (get-in (edn/read-string text) [:claimed :status])))
        (spit "test/fixtures/run-bundle.edn" text)
        (println "wrote test/fixtures/run-bundle.edn" (count text) "bytes, run" (:run-id run))))
    (finally (srv/stop! s) (fs/delete-tree root))))
