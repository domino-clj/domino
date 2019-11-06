(ns domino.validation)

(defn valid-model? [{:keys [model] :as ctx}]
  (assoc ctx :path-ids
             (reduce
               (fn [ids item]
                 (if (map? item)
                   (if-let [id (:id item)]
                     (if (contains? ids id)
                       (throw (ex-info (str "duplicate id " id " in the model") {:id id}))
                       (conj ids id)))
                   ids))
               #{}
               (mapcat flatten model))))

(defn path-id-present? [path-ids id]
  (or (contains? path-ids id)
      (throw (ex-info (str "no path found for " id " in the model") {:id id}))))

(defn valid-events? [{:keys [events path-ids] :as ctx}]
  (let [path-check-fn (partial path-id-present? path-ids)]
    (mapv
      (fn [{:keys [inputs outputs]}]
        (mapv path-check-fn inputs)
        (mapv path-check-fn outputs))
      events))
  ctx)

(defn validate-schema! [ctx]
  (-> ctx
      valid-model?
      valid-events?))