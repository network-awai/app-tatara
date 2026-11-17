(ns tatara.core.canonical
  "Deterministic text encoding of plain data, used before hashing.
  Maps and sets are ordered by the encoded form of their members. Floats are
  rejected because their text differs across hosts.")

(declare canonical)

(defn- enc-all [xs] (mapv canonical xs))

(defn canonical [x]
  (cond
    (nil? x) "nil"
    (boolean? x) (str x)
    (string? x) (pr-str x)
    (keyword? x) (str x)
    (integer? x) (str x)
    (number? x) (throw (ex-info "floats are not canonical" {:value x}))
    (map? x) (str "{" (apply str (interpose " " (map (fn [[k v]] (str k " " v))
                                                     (sort-by first (map (fn [[k v]] [(canonical k) (canonical v)]) x)))))
                  "}")
    (set? x) (str "#{" (apply str (interpose " " (sort (enc-all x)))) "}")
    (sequential? x) (str "[" (apply str (interpose " " (enc-all x))) "]")
    :else (throw (ex-info "unsupported value" {:value x}))))

(defn cid
  "Content id of data. ctx is {:hash f}, f: string -> lowercase hex digest.
  The id scheme is a placeholder, not the kotoba-git CID format."
  [{:keys [hash]} x]
  (str "cid:sha256:" (hash (canonical x))))
