(ns tatara.coordinator.b2
  "Backblaze B2 native API (v4; keys scoped with bucketIds need it), just what artifact publication needs
  (AWAI-3834): authorize, upload, download (read-back) and a time-limited
  download link. The key is scoped to one bucket with listFiles, readFiles,
  writeFiles and shareFiles, and no deleteFiles: this process can add versions,
  never remove them. Keys and tokens stay in memory; nothing here logs them."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.net URLEncoder)
           (java.security MessageDigest)
           (java.util Base64)))

(def default-api "https://api.backblazeb2.com")
(def ^:private client (delay (http/client {:connect-timeout 10000 :follow-redirects :normal})))
(def timeout-ms 300000)

(defn- digest-hex [algo ^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest (MessageDigest/getInstance algo) b))))
(defn sha1-hex [^bytes b] (digest-hex "SHA-1" b))
(defn sha256-hex [^bytes b] (digest-hex "SHA-256" b))

(defn encode-name
  "B2 file names are percent-encoded in headers and URLs, '/' kept."
  [s]
  (-> (URLEncoder/encode ^String s "UTF-8") (str/replace "+" "%20") (str/replace "%2F" "/")))

(defn- ok-json [what res]
  (when-not (= 200 (:status res))
    (throw (ex-info (str "b2 " what " failed") {:status (:status res)
                                                 :code (try (:code (json/parse-string (str (:body res)) true))
                                                            (catch Exception _ nil))})))
  (json/parse-string (:body res) true))

(defn authorize
  "{:key-id :key :bucket :api} -> session {:api-url :download-url :token :bucket :bucket-id}."
  [{:keys [key-id key bucket api]}]
  (let [basic (.encodeToString (Base64/getEncoder) (.getBytes (str key-id ":" key) "UTF-8"))
        r (ok-json "authorize"
                   (http/get (str (or api default-api) "/b2api/v4/b2_authorize_account")
                             {:headers {"authorization" (str "Basic " basic)} :throw false
                              :client @client :timeout 30000}))
        s (get-in r [:apiInfo :storageApi])
        b (some #(when (= bucket (:name %)) %) (get-in s [:allowed :buckets]))]
    (when-not b (throw (ex-info "b2 key is not scoped to the bucket" {:bucket bucket})))
    {:api-url (:apiUrl s) :download-url (:downloadUrl s) :token (:authorizationToken r)
     :bucket bucket :bucket-id (:id b)}))

(defn- api-post [{:keys [api-url token]} op body]
  (ok-json op (http/post (str api-url "/b2api/v4/" op)
                         {:headers {"authorization" token "content-type" "application/json"}
                          :body (json/generate-string body) :throw false :client @client :timeout 30000})))

(defn upload!
  "Upload bytes as file-name. B2 checks the sha1; the sha256 rides along as
  file info. Returns {:file-id :sha1 :sha256 :size}."
  [session file-name ^bytes data content-type]
  (let [{:keys [uploadUrl authorizationToken]} (api-post session "b2_get_upload_url" {:bucketId (:bucket-id session)})
        sha1 (sha1-hex data) sha256 (sha256-hex data)
        r (ok-json "upload"
                   (http/post uploadUrl {:headers {"authorization" authorizationToken
                                                   "x-bz-file-name" (encode-name file-name)
                                                   "content-type" content-type
                                                   "x-bz-content-sha1" sha1
                                                   "x-bz-info-sha256" sha256}
                                         :body data :throw false :client @client :timeout timeout-ms}))]
    {:file-id (:fileId r) :sha1 sha1 :sha256 sha256 :size (count data)}))

(defn download
  "Bytes of the latest version of file-name (read-back check)."
  ^bytes [{:keys [download-url token bucket]} file-name]
  (let [res (http/get (str download-url "/file/" bucket "/" (encode-name file-name))
                      {:headers {"authorization" token} :as :bytes :throw false :client @client :timeout timeout-ms})]
    (when-not (= 200 (:status res)) (throw (ex-info "b2 download failed" {:status (:status res) :file file-name})))
    (:body res)))

(defn link
  "A download URL for file-name valid for ttl-s seconds (at most 7 days),
  authorized for exactly that name."
  [{:keys [download-url bucket bucket-id] :as session} file-name ttl-s]
  (let [r (api-post session "b2_get_download_authorization"
                    {:bucketId bucket-id :fileNamePrefix file-name
                     :validDurationInSeconds (min 604800 (max 1 ttl-s))})]
    (str download-url "/file/" bucket "/" (encode-name file-name)
         "?Authorization=" (URLEncoder/encode ^String (:authorizationToken r) "UTF-8"))))
