(ns domino.effects
  (:require
    [domino.events :as events]
    [domino.util :refer [generate-sub-paths]]))

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

(defn execute-effect! [{:domino.core/keys [model db] :as ctx} {:keys [inputs handler] :as effect}]
  (try
    (handler ctx (events/get-db-paths model db inputs))
    (catch #?(:clj Exception :cljs js/Error) e
      (throw (ex-info "failed to execute effect" {:effect effect :context ctx :db db} e)))))

(defn execute-effects!
  [{:domino.core/keys [change-history effects] :as ctx}]
  (reduce
    (fn [visited effect]
      (if-not (contains? visited effect)
        (do (execute-effect! ctx effect)
            (conj visited effect))
        visited))
    #{}
    (->> (map first change-history)
         (mapcat generate-sub-paths)
         distinct
         (change-effects effects))))

(defn try-effect [{:keys [handler] :as effect} ctx db old-outputs]
  (try
    (handler ctx old-outputs)
    (catch #?(:clj Exception :cljs js/Error) e
      (throw (ex-info "failed to execute effect" {:effect effect :context ctx :db db} e)))))

(defn effect-outputs-as-changes [{:domino.core/keys [effects-by-id db model] :as ctx} effect-ids]
  (let [id->effect  #(get-in effects-by-id [%])
        id->path    #(get-in model [:id->path %])
        res->change (juxt (comp id->path first) second)
        old-outputs #(events/get-db-paths model db (map id->path (:outputs %)))
        run-effect  #(try-effect % ctx db (old-outputs %))]
    (->> effect-ids
         (map id->effect)
         (map run-effect)
         (mapcat identity)
         (map res->change))))
