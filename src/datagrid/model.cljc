(ns datagrid.model
  (:require
    [clojure.set :refer [map-invert]]))

(defn paths-by-id
  ([root] (paths-by-id {} [] root))
  ([mapped-paths path [segment opts & children]]
   (if segment
     (let [path         (conj path segment)
           mapped-paths (if-let [id (:id opts)]
                          (assoc mapped-paths id path)
                          mapped-paths)]
       (if-not (empty? children)
         (apply merge (map (partial paths-by-id mapped-paths path) children))
         mapped-paths))
     mapped-paths)))

(defn model->paths [model]
  (let [id->path (apply merge (map paths-by-id model))]
    {:id->path id->path
     :path->id (map-invert id->path)}))

(defn path-for-id [{:keys [path->id]} path]
  (loop [path-segment path]
    (when-not (empty? path-segment)
      (if-let [id (get path->id path-segment)]
        id
        (recur (butlast path-segment))))))
