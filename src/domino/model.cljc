(ns domino.model
  (:require
    [clojure.set :refer [map-invert]]))

(defn normalize [path-segment]
  (if (map? (second path-segment))
    path-segment
    (into [(first path-segment) {}] (rest path-segment))))

(defn paths-by-id
  ([root] (paths-by-id {} [] root))
  ([mapped-paths path path-segment]
   (let [[segment opts & children] (normalize path-segment)]
     (if segment
       (let [path         (conj path segment)
             mapped-paths (if-let [id (:id opts)]
                            (assoc mapped-paths id path)
                            mapped-paths)]
         (if-not (empty? children)
           (apply merge (map (partial paths-by-id mapped-paths path) children))
           mapped-paths))
       mapped-paths))))

(defn model->paths [model]
  (let [id->path (apply merge (map paths-by-id model))]
    {:id->path id->path
     :path->id (map-invert id->path)}))

(defn id-for-path [{:keys [path->id]} path]
  (loop [path-segment path]
    (when-not (empty? path-segment)
      (if-let [id (get path->id path-segment)]
        id
        (recur (butlast path-segment))))))

;;TODO path segment options can contain :pre and :post keys
;;attach these to the handler map, and check them when running the handler

;;TODO ensure all keys are unique!

(defn connect [{:keys [id->path]} events]
  (let [path-for-id (fn [id] (get id->path id))]
    (mapv
      (fn [event]
        (-> event
            (update :inputs #(map path-for-id %))
            (update :outputs #(map path-for-id %))))
      events)))

