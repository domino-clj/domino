(ns domino.validation)

(defn check-valid-model [{:keys [model] :as ctx}]
  (reduce
    (fn [{:keys [path-ids] :as ctx} item]
      (if (map? item)
        (if-let [id (:id item)]
          (if (contains? path-ids id)
            (update ctx :errors conj [(str "duplicate id " id " in the model") {:id id}])
            (update ctx :path-ids conj id)))
        ctx))
    ctx
    (mapcat flatten model)))

(defn check-valid-events [{:keys [events path-ids] :as ctx}]
  (let [id-in-path? (partial contains? path-ids)]
    (reduce
      (fn [ctx id]
        (if (id-in-path? id)
          ctx
          (update ctx :errors conj [(str "no path found for " id " in the model") {:id id}])))
      ctx
      (mapcat (comp flatten (juxt :inputs :outputs)) events))))

(defn maybe-throw-exception [{:keys [errors]}]
  (when (not-empty errors)
    (throw (ex-info (str "errors found while validating schema") {:errors errors}))))

(defn validate-schema [ctx]
  (-> ctx
      (assoc :path-ids #{}
             :errors [])
      check-valid-model
      check-valid-events))