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

(defn wrap-pre [handler pre]
  (let [[interceptor & pre] (reverse pre)]
    (reduce
      (fn [handler interceptor]
        (interceptor handler))
      (interceptor handler)
      pre)))

(defn wrap-post [post]
  (reduce
    (fn [handler interceptor]
      (interceptor handler))
    identity
    (reverse post)))

(defn wrap [handler pre post]
  (cond
    (and (empty? pre) (empty? post))
    handler

    (empty? post)
    (wrap-pre handler pre)

    (empty? pre)
    (let [post (wrap-post post)]
      (fn [ctx inputs outputs]
        (post (handler ctx inputs outputs))))

    :else
    (let [handler (wrap-pre handler pre)
          post (wrap-post post)]
      (fn [ctx inputs outputs]
        (post (handler ctx inputs outputs))))))

;;todo ensure that :pre/:post from all the parent path segments are included as well
(defn ids-to-interceptors [id->opts ids k]
  (->> ids
       (mapcat #(get-in id->opts [% k]))
       (remove nil?)
       (not-empty)))

;;TODO ensure all keys are unique!
(defn connect-events [{:keys [id->path id->opts]} events]
  (let [path-for-id (fn [id] (get id->path id))]
    (mapv
      (fn [{:keys [inputs] :as event}]
        (let [pre  (ids-to-interceptors id->opts inputs :pre)
              post (ids-to-interceptors id->opts inputs :post)]
          (-> event
              (update :inputs #(map path-for-id %))
              (update :outputs #(map path-for-id %))
              (update :handler wrap pre post))))
      events)))

(defn connect-effects [{:keys [id->path]} events]
  (let [path-for-id (fn [id] (get id->path id))]
    (mapv
      (fn [event]
        (-> event
            (update :inputs #(map path-for-id %))
            (update :outputs #(map path-for-id %))))
      events)))

