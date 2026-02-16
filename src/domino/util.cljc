(ns domino.util)

(defn generate-sub-paths
  "Given a `path`, generate a list of all sub-paths including `path`"
  [path]
  (let [path (vec path)]
    (if (empty? path)
      []
      (loop [paths [path]
             path  path]
        (if (> (count path) 1)
          (recur (conj paths (pop path)) (pop path))
          paths)))))

(defn map-by-id [items]
  (into {} (comp (filter :id) (map (juxt :id identity))) items))