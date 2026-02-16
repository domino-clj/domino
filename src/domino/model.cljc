(ns domino.model
  (:require
    [domino.util :as util]))

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
         (if (seq children)
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
  (loop [path (vec path)]
    (when (seq path)
      (or (get path->id path)
          (recur (pop path))))))

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
    (and (not pre) (not post))
    handler

    (not post)
    (wrap-pre handler pre)

    (not pre)
    (let [post (wrap-post post)]
      (fn [ctx inputs outputs]
        (post (util/resolve-result (handler ctx inputs outputs)))))

    :else
    (let [handler (wrap-pre handler pre)
          post    (wrap-post post)]
      (fn [ctx inputs outputs]
        (post (util/resolve-result (handler ctx inputs outputs)))))))

(defn ids-to-interceptors
  "finds the interceptors based on the provided ids
  the lookup will consider parent path segments"
  [path->id id->path id->opts ids k]
  (->> (map id->path ids)
       (mapcat util/generate-sub-paths)
       (mapcat #(get-in id->opts [(path->id %) k]))
       (distinct)
       (remove nil?)
       (not-empty)))

(defn connect-events [{:keys [path->id id->path id->opts]} events]
  (mapv
    (fn [{:keys [inputs] :as event}]
      (let [pre  (ids-to-interceptors path->id id->path id->opts inputs :pre)
            post (ids-to-interceptors path->id id->path id->opts inputs :post)]
        (-> event
            (update :inputs #(mapv id->path %))
            (update :outputs #(mapv id->path %))
            (update :handler wrap pre post))))
    events))

(defn connect-effects [{:keys [id->path]} effects]
  (mapv
    (fn [effect]
      (-> effect
          (update :inputs #(mapv id->path %))
          (update :outputs #(mapv id->path %))))
    effects))

