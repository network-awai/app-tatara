(ns tatara.coordinator.publish-test
  "AWAI-3834: a passed run's artifacts go to B2 with a sha256 manifest, are
  read back, and get a commit status that links to the artifact. Against an
  in-process fake of the B2 native API (it keeps every version, has no delete)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.server :as hs]
            [tatara.core.canonical :as c]
            [tatara.coordinator.b2 :as b2]
            [tatara.coordinator.client :as cl]
            [tatara.coordinator.crypto :as crypto]
            [tatara.coordinator.e2e-test :as e2e]
            [tatara.coordinator.http :as srv]
            [tatara.coordinator.publish :as publish]
            [tatara.coordinator.service :as svc])
  (:import (java.net URLDecoder)))

(def ^:dynamic *root* nil)
(use-fixtures :each (fn [t] (let [d (str (fs/create-temp-dir {:prefix "tatara-publish-"}))]
                              (binding [*root* d] (try (t) (finally (fs/delete-tree d)))))))

(defn fake-b2
  "Minimal B2: authorize (Basic key), upload with sha1 check, download, download auth.
  corrupt? flips one byte on download, to prove the read-back catches it."
  [files corrupt?]
  (let [port (atom nil)
        body-bytes (fn [req] (some-> (:body req) (.readAllBytes)))
        js (fn [m] {:status 200 :headers {"content-type" "application/json"} :body (json/generate-string m)})
        h (fn [req]
            (let [uri (:uri req) base (str "http://127.0.0.1:" @port)]
              (cond
                (= uri "/b2api/v4/b2_authorize_account")
                (if (= (get-in req [:headers "authorization"])
                       (str "Basic " (.encodeToString (java.util.Base64/getEncoder) (.getBytes "kid:secret" "UTF-8"))))
                  (js {:accountId "acct" :authorizationToken "tok"
                       :apiInfo {:storageApi {:apiUrl base :downloadUrl base
                                              :allowed {:buckets [{:id "bkt" :name "tatara-apk-artifacts"}]}}}})
                  {:status 401 :body "{\"code\":\"unauthorized\"}"})
                (= uri "/b2api/v4/b2_get_upload_url") (js {:uploadUrl (str base "/upload") :authorizationToken "up"})
                (= uri "/upload")
                (let [data (body-bytes req) n (URLDecoder/decode (get-in req [:headers "x-bz-file-name"]) "UTF-8")]
                  (if (= (b2/sha1-hex data) (get-in req [:headers "x-bz-content-sha1"]))
                    (do (swap! files update n (fnil conj []) data) (js {:fileId (str "id-" (count @files)) :fileName n}))
                    {:status 400 :body "{\"code\":\"bad_sha1\"}"}))
                (= uri "/b2api/v4/b2_get_download_authorization") (js {:authorizationToken "dl-tok"})
                (str/starts-with? uri "/file/tatara-apk-artifacts/")
                (let [n (URLDecoder/decode (subs uri (count "/file/tatara-apk-artifacts/")) "UTF-8")
                      ^bytes d (last (get @files n))]
                  (if d
                    (let [out (aclone d)] (when corrupt? (aset out 0 (unchecked-byte (inc (aget out 0)))))
                      {:status 200 :body (java.io.ByteArrayInputStream. out)})
                    {:status 404 :body "{}"}))
                :else {:status 404 :body "{}"})))
        stop (hs/run-server h {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (reset! port (hs/server-port stop))
    {:stop stop :api (str "http://127.0.0.1:" @port)}))

(def pipeline {:tier :t2 :network :fetch :toolchain :android-v1 :quorum 1
               :status-context "tatara/apk-build"
               :steps [["sh" "scripts/android-apk.sh"]] :artifacts ["out"]
               :publish {:context "tatara/apk" :link "out"}})
(def event {:forge "github" :repo "cloud-itonami/app-example" :ref "refs/heads/main"
            :sha "0123456789abcdef0123456789abcdef01234567" :pipeline-cid (c/cid crypto/ctx pipeline)})

(defn passed-run! [s]
  (let [ca (cl/client (e2e/base s) e2e/a e2e/clock)
        run (binding [e2e/*root* *root*] (e2e/submit s {:event event :pipeline pipeline}))
        job (:body (cl/lease ca [:t2]))]
    (e2e/do-job ca job "APK-BYTES")
    (get-in run [:body :run-id])))

(defn start [] (srv/start! {:root *root* :allow #{(:did e2e/a)} :ttl-ms 60000
                            :runner-toolchains {(:did e2e/a) #{:android-v1}}}
                           {:clock e2e/clock :admin-token e2e/admin}))

(deftest a-passed-run-is-published-read-back-and-linked
  (let [files (atom {}) b (fake-b2 files false) s (start)
        b2cfg {:api (:api b) :key-id "kid" :key "secret" :bucket "tatara-apk-artifacts" :link-ttl-s 3600}]
    (try
      (let [id (passed-run! s)
            [r] (publish/publish-once s b2cfg (publish/session-provider b2cfg))
            pub (get-in @(:state s) [:publications id])
            prefix (str "cloud-itonami/app-example/0123456789abcdef0123456789abcdef01234567/" (publish/run-key id) "/")
            manifest (json/parse-string (String. ^bytes (last (get @files (str prefix "manifest.json"))) "UTF-8") true)]
        (is (= :published (:status r)))
        (testing "the artifact and a manifest with its size and sha256 are in the bucket"
          (is (= "APK-BYTES" (String. ^bytes (last (get @files (str prefix "out"))) "UTF-8")))
          (is (= [{:artifact "out" :object (str prefix "out") :size 9
                   :sha256 (b2/sha256-hex (.getBytes "APK-BYTES" "UTF-8"))}]
                 (:files manifest)))
          (is (= "debug keystore only; no signing key on CI" (:signing manifest))))
        (testing "every object was read back"
          (is (= 2 (:read-back pub))))
        (testing "the commit status names its own context and links to the artifact"
          (let [acts (map :action (svc/pending-actions @(:state s)))
                apk (some #(when (= "tatara/apk" (:context %)) %) acts)]
            (is (= "success" (:state apk)))
            (is (str/includes? (:target-url apk) (str "/file/tatara-apk-artifacts/" prefix "out?Authorization=")))
            (is (some #(= "tatara/apk-build" (:context %)) acts) "the build status uses the pipeline's context")))
        (testing "a published run is not published again"
          (is (= [] (publish/publish-once s b2cfg (publish/session-provider b2cfg)))))
        (testing "the publications view leaves the link out"
          (is (not (str/includes? (slurp (str (e2e/base s) "/v1/publications")) "Authorization")))))
      (finally (srv/stop! s) (hs/server-stop! (:stop b))))))

(deftest a-read-back-mismatch-is-retried-then-an-error-status
  (let [files (atom {}) b (fake-b2 files true) s (start)
        b2cfg {:api (:api b) :key-id "kid" :key "secret" :bucket "tatara-apk-artifacts" :max-attempts 2}]
    (try
      (let [id (passed-run! s)
            sp (publish/session-provider b2cfg)]
        (is (= :retry (:status (first (publish/publish-once s b2cfg sp)))))
        (is (= :failed (:status (first (publish/publish-once s b2cfg sp)))))
        (is (= "read-back mismatch" (get-in @(:state s) [:publications id :error :message])))
        (let [apk (some #(when (= "tatara/apk" (:context %)) %) (map :action (svc/pending-actions @(:state s))))]
          (is (= "error" (:state apk)))
          (is (nil? (:target-url apk))))
        (is (= [] (publish/publish-once s b2cfg sp)) "given up: not retried forever"))
      (finally (srv/stop! s) (hs/server-stop! (:stop b))))))

(deftest a-wrong-key-publishes-nothing
  (let [files (atom {}) b (fake-b2 files false) s (start)
        b2cfg {:api (:api b) :key-id "kid" :key "wrong" :bucket "tatara-apk-artifacts"}]
    (try
      (passed-run! s)
      (is (= :retry (:status (first (publish/publish-once s b2cfg (publish/session-provider b2cfg))))))
      (is (empty? @files))
      (finally (srv/stop! s) (hs/server-stop! (:stop b))))))

(deftest pipelines-without-publish-are-ignored
  (is (= [] (publish/due {:runs {"r1" {:status :reached :pipeline {:steps []}
                                       :outcome {:verdict-cid "v"} :entries [{:verdict-cid "v" :result :passed}]}}}))))

(deftest queue-depth-by-toolchain
  (let [st {:runs {"a" {:status :pending :pipeline {:tier :t2}}
                   "b" {:status :pending :pipeline {:tier :t2 :toolchain :android-v1}}}
            :jobs {"ja" {:run-id "a" :status :queued} "jb" {:run-id "b" :status :queued}}}]
    (is (= {:tier :t2 :queued 1 :leased 0} (svc/queue-depth st 0 :t2)))
    (is (= {:tier :t2 :queued 1 :leased 0 :toolchain :android-v1} (svc/queue-depth st 0 :t2 :android-v1)))))

(deftest admin-blob-upload-needs-the-admin-token
  (let [s (start) data (.getBytes "tarball" "UTF-8") sha (crypto/sha256-bytes data)
        put (fn [auth] (:status (babashka.http-client/put (str (e2e/base s) "/v1/admin/blob/" sha)
                                                          {:headers (if auth {"authorization" auth} {})
                                                           :body data :throw false})))]
    (try
      (is (= 401 (put nil)))
      (is (= 401 (put "Bearer nope")))
      (is (= 200 (put (str "Bearer " e2e/admin))))
      (finally (srv/stop! s)))))
