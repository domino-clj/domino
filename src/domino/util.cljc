(ns domino.util)

(defn generate-sub-paths
  "Given a `path`, generate a list of all sub-paths including `path`"
  [path]
  (loop [paths []
         path  path]
    (if (not-empty path)
      (recur (conj paths (vec path)) (drop-last path))
      paths)))

(defn map-by-id [items]
  (->> items
       (filter #(contains? % :id))
       (map (juxt :id identity))
       (into {})))

(defn dissoc-in [m [k & ks]]
  (if-some [submap (and ks
                        (some-> m
                                (get k)
                                (dissoc-in ks)
                                (not-empty)))]
    (assoc m k submap)
    (dissoc m k)))
