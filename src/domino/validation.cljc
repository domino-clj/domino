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

(defn- check-ids-in-model [{:keys [path-ids] :as ctx} ids]
  (reduce
    (fn [ctx id]
      (if (contains? path-ids id)
        ctx
        (update ctx :errors conj [(str "no path found for " id " in the model") {:id id}])))
    ctx
    ids))

(defn check-valid-events [{:keys [events] :as ctx}]
  (check-ids-in-model ctx (mapcat (comp flatten (juxt :inputs :outputs)) events)))

(defn check-valid-effects [{:keys [effects] :as ctx}]
  (check-ids-in-model ctx (mapcat (comp flatten :inputs) effects)))

(defn maybe-throw-exception [{:keys [errors]}]
  (when (seq errors)
    (throw (ex-info (str "errors found while validating schema") {:errors errors}))))

(defn validate-schema [ctx]
  (-> ctx
      (assoc :path-ids #{}
             :errors [])
      check-valid-model
      check-valid-events
      check-valid-effects))