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

(defn change-effects [effects changes]
  (mapcat (fn [[path]] (get effects path))
          changes))

(defn execute-effect! [{:datagrid.core/keys [db] :as ctx} {:keys [inputs handler]}]
  (handler ctx (map #(get-in db %) inputs)))

(defn execute-effects!
  [{:keys [changes] :datagrid.core/keys [effects] :as ctx}]
  (reduce
    (fn [visited effect]
      (if-not (contains? visited effect)
        (do (execute-effect! ctx effect)
            (conj visited effect))
        visited))
    #{}
    (change-effects effects changes)))