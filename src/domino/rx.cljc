(ns domino.rx
  (:require [clojure.set :refer [union]]))

;; NOTE: may want to refactor to allow optional params to a reaction (e.g. lookup)

(def ^:dynamic *debug* true)

(defn create-reactive-map [m]
  {::root {:value m
           :allow-set? true}})


(defn run-reaction-impl [m rxn]
  (let [input->value #(get-in m [% :value])
        args (case (:args-style rxn :vector)
               :vector (mapv input->value (:args rxn))
               :single [(input->value (:args rxn))]
               :map    [(reduce-kv
                         (fn [m k v]
                           (assoc m k (input->value v)))
                         {}
                         (:args rxn))]
               :rx-map (reduce
                        (fn [m i]
                          (assoc m i (input->value i)))
                        {}
                        (:args rxn)))
        compute (comp (if (or *debug* (:debug? rxn))
                        (fn [r] (println "Computing reaction: " (:id rxn)) r)
                        identity)
                      (:fn rxn))]
    (assoc
     rxn
     :value
     (apply compute args))))

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
                  (partial run-reaction-impl m)))))))

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


;; TODO: get-upstream
;; TODO: get-downstream
;; TODO: print-graph
;; NOTE: Should we enforce `::root` as the single changeable point?
;;       (i.e. any other 'root' like things would be convenience fns around top-level keys)
;; TODO: allow for nested/dependent reactions. (i.e. one reaction for each element in a collection)

(defn args->inputs [args-style args]
  (case args-style
    (:rx-map :vector)
    args

    :single
    [args]

    :map
    (vec (vals args))))

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
  ([m rx]
   (add-reaction! m (:id rx) (:args rx) (:fn rx) (dissoc rx
                                                         :id
                                                         :inputs
                                                         :fn)))
  ([m id f]
   (add-reaction! m id ::root f {:args-style :single}))
  ([m id args f]
   (add-reaction! m id args f {}))
  ([m id args f opts]
   (let [args-style (or (:args-style opts)
                        (cond
                          (map? args) :map
                          (keyword? args) :single
                          (contains? m args) :single
                          (vector? args) :vector
                          :else (throw (ex-info "Unknown args style!" {:args args}))))
         inputs (args->inputs args-style args)]
     (-> m
         (annotate-inputs id inputs f)
         (assoc id (merge
                    opts
                    {:id id
                     :args-style args-style
                     :args args
                     :inputs inputs
                     :fn f}))
         (cond->
             (not (:lazy? opts)) (compute-reaction id))))))

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