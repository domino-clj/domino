(ns domino.util)

(defn generate-sub-paths
  "Given a `path`, generate a list of all sub-paths including `path`"
  [path]
  (loop [paths []
         path path]
    (if (not-empty path)
      (recur (conj paths (vec path)) (drop-last path))
      paths)))
