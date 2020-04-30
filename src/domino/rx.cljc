(ns domino.rx
  (:require [clojure.set :refer [union]]))

;; NOTE: may want to refactor to allow optional params to a reaction (e.g. lookup)

(def ^:dynamic *debug* true)

(defn create-reactive-map [m]
  {::root {:value m
           :allow-set? true}})

(defn compute-reaction [m id]
  (let [rxn (get m id)]
    (cond
      (nil? rxn)
      (throw
       (ex-info (str "Reaction with id: " id " is not registered!")
                {:id id}))

      (contains? rxn :value)
      m

      :else
      (if-some [input-to-compute
                (->> (:inputs rxn)
                     (map (partial get m))
                     (some #(when-not (contains? % :value) %)))]
        (-> m
            (compute-reaction input-to-compute)
            (compute-reaction id))
        (if (nil? (:fn rxn))
          (throw
           (ex-info
            (str "No function defined for reaction: " id "!")
            {:reaction rxn
             :id id}))
          (update m id
                  assoc
                 :value
                 ((comp (if (or *debug* (:debug? rxn)) (fn [r] (println "Computing reaction: " id) r) identity) (:fn rxn))
                  (mapv
                   (comp :value
                         m)
                   (:inputs rxn)))))))))

(defn compute-reaction! [m id]
  (-> m
      (update id dissoc :value)
      (compute-reaction id)))

(defn get-reaction [m id]
  (if-let [rxn (get m id)]
    (if (contains? rxn :value)
     (:value rxn)
     (do
       ;;TODO Convert to log/warn cljc
       (println "[WARN(println)] value isn't computed. Run compute-reaction to update your reactive map and cache the computation!")
       (get-reaction (compute-reaction m id) id)))
    (throw (ex-info (str "Reaction with id: " id " is not registered!")
                    {:id id}))))



(defn- annotate-inputs [m id inputs f]
  (reduce
   (fn [m input]
     (if (contains? m input)
       (update m input update :watchers (fnil conj #{}) id)
       (throw (ex-info
               (str "Failed to add reaction: " id "\nInput reaction: " input " is not registered!")
               {:id id
                :inputs inputs
                :missing-reaction input}))))
   m
   inputs))

(defn add-reaction!
  ([m id f]
   (add-reaction! m id [::root] (comp f first) {}))
  ([m id inputs f]
   (add-reaction! m id inputs f {}))
  ([m id inputs f opts]
   (-> m
       (annotate-inputs id inputs f)
       (assoc id (merge
                  opts
                  {:inputs inputs
                   :fn f}))
       (cond->
           (not (:lazy? opts)) (compute-reaction id)))))

(defn clear-watchers [m trigger-id]
  (let [rxn (get m trigger-id)]
    (reduce
     ;; NOTE: this is re-running reactions for each input that changes
     ;;       we should wait for all inputs to resolve, and then resume.
     (fn [m id]
       (let [{:keys [lazy? value watchers] :as inner-rxn} (get m id)]
         (if lazy?
           (-> m
               (update id dissoc :value)
               (clear-watchers id))
           (let [m (compute-reaction! m id)]
             (if (= (get-reaction m id) value)
               m
               (clear-watchers m id))))))
     m
     (:watchers rxn))))

(defn set-value
  ([m v]
   (set-value m ::root v))
  ([m id v]
   (let [rxn (get m id)]
     (if (not (:allow-set? rxn))
       (throw (ex-info (str "Attempted to call `set-value` on a reaction where it is not explicitly allowed!\n"
                            "Set `:allow-set?` to true on reaction: " id " if this is desired.")
                       {:reaction rxn
                        :id id}))
       (-> m
           (update id assoc :value v)
           (clear-watchers id))))))

(defn update-root [m f & args]
  (set-value m ::root (apply f (get-reaction m ::root) args)))

(defn update-value [m id f & args]
  (set-value m id (apply f (get-reaction m id) args)))

(comment
  ;; These two functions can force recomputation of all reaction fns.
  ;; These shouldn't be enabled unless absolutely neccessary, as they may cause issues with eagerly computed reactions
  (defn transitive-inputs [m id]
    (loop [inputs #{id}
           rxns [(get m id)]]
      (let [new-inputs (into #{}
                             (comp
                              (mapcat :inputs)
                              (remove inputs))
                             rxns)]
        (if (empty? new-inputs)
          inputs
          (recur (into inputs new-inputs) (map (partial get m) new-inputs))))))

  (defn compute-reactions! [m id]
    (-> (reduce
         (fn [m id]
           (update m id
                   (fn [rxn]
                     (if (:fn rxn)
                       (dissoc rxn :value)
                       rxn))))
         m
         (transitive-inputs m id))
        (compute-reaction id))))
