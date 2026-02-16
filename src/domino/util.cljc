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

(defn resolve-result
  "If x is derefable (delay, future, promise, atom), derefs it.
  Otherwise returns x as-is."
  [x]
  (if (and (some? x)
           #?(:clj  (instance? clojure.lang.IDeref x)
              :cljs (satisfies? IDeref x)))
    (deref x)
    x))