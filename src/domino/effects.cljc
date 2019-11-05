(ns domino.effects)

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

(defn execute-effect! [{:domino.core/keys [db] :as ctx} {:keys [inputs handler]}]
  (handler ctx (map #(get-in db %) inputs)))

(defn build-change-paths
  [path]
  (loop [paths []
         path path]
    (if (not-empty path)
      (recur (conj paths (vec path)) (drop-last path))
      paths)))

(defn execute-effects!
  [{:keys [change-history] :domino.core/keys [effects] :as ctx}]
  (reduce
    (fn [visited effect]
      (if-not (contains? visited effect)
        (do (execute-effect! ctx effect)
            (conj visited effect))
        visited))
    #{}
    (->> (map first change-history)
         (mapcat build-change-paths)
         (change-effects effects)))) ;; TODO: double check this approach when changes is a sequential history
