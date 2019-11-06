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
                            (assoc mapped-paths id {:path path
                                                    :opts opts})
                            mapped-paths)]
         (if-not (empty? children)
           (apply merge (map (partial paths-by-id mapped-paths path) children))
           mapped-paths))
       mapped-paths))))

(defn model->paths [model]
  (reduce
    (fn [model [id {:keys [path opts]}]]
      (-> model
          (update :id->path assoc id path)
          (update :path->id assoc path id)
          (update :id->opts assoc id opts)))
    {}
    (apply merge (map paths-by-id model))))

(defn id-for-path [{:keys [path->id]} path]
  (loop [path-segment path]
    (when-not (empty? path-segment)
      (if-let [id (get path->id path-segment)]
        id
        (recur (butlast path-segment))))))

;;TODO path segment options can contain :pre and :post keys
;;attach these to the handler map, and check them when running the handler

(defn wrap [handler interceptors]
  (let [[interceptor & interceptors] (reverse interceptors)]
    (reduce
      (fn [handler interceptor]
        (interceptor handler))
      (interceptor handler)
      interceptors)))

;;TODO ensure all keys are unique!
(defn connect [{:keys [id->path]} events]
  (let [path-for-id (fn [id] (get id->path id))]
    (mapv
      (fn [event]
        (-> event
            (update :inputs #(map path-for-id %))
            (update :outputs #(map path-for-id %))))
      events)))

