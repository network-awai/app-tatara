(ns tatara.coordinator.store
  "Durable state and content-addressed storage on the local filesystem.
  State is one EDN file replaced by write-temp, fsync, rename, fsync(dir):
  a reader after a crash sees either the old or the new state, never a mix.
  Callers persist before answering a request (ADR-0001: persist before expose)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io FileOutputStream)
           (java.nio.channels FileChannel)
           (java.nio.file Files OpenOption Paths StandardCopyOption StandardOpenOption)))

(defn- fsync-dir! [^java.io.File dir]
  (with-open [c (FileChannel/open (Paths/get (.getPath dir) (into-array String []))
                                  (into-array OpenOption [StandardOpenOption/READ]))]
    (.force c true)))

(defn write-atomic!
  "Replace path with bytes durably. Existing immutable objects are not rewritten."
  [path ^bytes data]
  (let [f (io/file path) dir (.getParentFile f)
        tmp (io/file dir (str "." (.getName f) ".tmp-" (System/nanoTime)))]
    (.mkdirs dir)
    (with-open [o (FileOutputStream. tmp)]
      (.write o data) (.flush o) (.force (.getChannel o) true))
    (Files/move (.toPath tmp) (.toPath f)
                (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (fsync-dir! dir)))

(defn state-file [root] (io/file root "state.edn"))

(defn load-state [root]
  (let [f (state-file root)]
    (if (.exists f) (edn/read-string (slurp f)) nil)))

(defn save-state! [root state]
  (write-atomic! (state-file root) (.getBytes (pr-str state) "UTF-8")))

(defn- object-file [root kind id]
  (io/file root kind (str (subs id 0 2)) id))

(defn blob-path [root sha] (object-file root "blobs" sha))
(defn has-blob? [root sha] (.exists (blob-path root sha)))
(defn put-blob! [root sha ^bytes data]
  (when-not (has-blob? root sha) (write-atomic! (blob-path root sha) data)))
(defn read-blob [root sha]
  (let [f (blob-path root sha)] (when (.exists f) (Files/readAllBytes (.toPath f)))))

(defn- cid-file-id [cid] (subs cid (count "cid:sha256:")))
(defn has-object? [root cid]
  (and (string? cid) (.startsWith ^String cid "cid:sha256:")
       (.exists (object-file root "objects" (cid-file-id cid)))))
(defn put-object! [root cid ^String canonical-text value]
  (when-not (has-object? root cid)
    (write-atomic! (object-file root "objects" (cid-file-id cid))
                   (.getBytes (pr-str {:canonical canonical-text :value value}) "UTF-8"))))
(defn read-object [root cid]
  (when (has-object? root cid)
    (:value (edn/read-string (slurp (object-file root "objects" (cid-file-id cid)))))))
