(ns datagrid.effects)

(defn effects-by-paths [effects]
  (reduce
    (fn [out {:keys [inputs] :as effect}]
      (reduce
        (fn [effects path]
          (update effects path (fnil conj []) effect))
        out
        inputs))
    {}
    effects))

(defn execute-effects!
  [{:keys [changes] :datagrid.core/keys [effects] :as ctx}]
  (prn changes effects)
  (reduce
    (fn [visited {:keys [inputs handler] :as effect}]
      (prn (map changes inputs))
      (if-not (contains? visited effect)
        (do (handler ctx (map changes inputs))
            (conj visited effect))
        visited))
    #{}
    (mapcat (fn [[path]] (get effects path)) changes)))