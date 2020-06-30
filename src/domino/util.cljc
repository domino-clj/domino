(ns domino.util
  #?(:cljs (:refer-clojure :exclude [random-uuid]))
  #?(:clj (:import java.util.UUID)))

(def empty-queue
  #?(:clj  clojure.lang.PersistentQueue/EMPTY
     :cljs cljs.core/PersistentQueue.EMPTY))

#?(:clj
   (defmethod clojure.core/print-method clojure.lang.PersistentQueue
     [queue writer]
     (.write writer (str "#<PersistentQueue: " (pr-str (vec queue)) ">"))))

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

(defn random-uuid []
  #?(:clj (UUID/randomUUID)
     :cljs (clojure.core/random-uuid)))
