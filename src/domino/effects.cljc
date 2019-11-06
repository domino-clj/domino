(ns domino.effects
  (:require
    [domino.graph :as graph]))

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
  (mapcat (fn [path] (get effects path))
          changes))

(defn execute-effect! [{:domino.core/keys [model db] :as ctx} {:keys [inputs handler]}]
  (handler ctx (graph/get-db-paths model db inputs)))

(defn execute-effects!
  [{:keys [change-history] :domino.core/keys [effects] :as ctx}]
  (reduce
    (fn [visited effect]
      (if-not (contains? visited effect)
        (do (execute-effect! ctx effect)
            (conj visited effect))
        visited))
    #{}
    (change-effects effects (distinct (map first change-history))))) ;; TODO: double check this approach when changes is a sequential history
