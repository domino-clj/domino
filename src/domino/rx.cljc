(ns domino.rx
  (:require [clojure.set :refer [union]]))

;; ==============================================================================
;; ABOUT
;; this namespace provides a reactive-map abstraction based on reagent/re-frame.
;; Instead of using state, we expose a means of registering reactions along with
;; an update function. The update function triggers recomputation of registered
;; reactions from changed pieces of state. It will run any reactions with changed
;; inputs exactly once.
;; ==============================================================================

(defn create-reactive-map [m]
  {::root {:value m
           :allow-set? true}
   ::rx-matcher {}})

(defn run-reaction-impl [m rxn]
  (let [input->value #(get-in m [% :value])
        args (case (:args-format rxn :vector)
               :vector (mapv input->value (:args rxn))
               :single [(input->value (:args rxn))]
               :map    [(reduce-kv
                         (fn [m k v]
                           (assoc m k (input->value v)))
                         {}
                         (:args rxn))]
               :rx-map [(reduce
                         (fn [m i]
                           (assoc m i (input->value i)))
                         {}
                         (:args rxn))])
        compute (:fn rxn)]
    (assoc
     rxn
     :value
     (apply compute args))))

(declare add-reaction!)

(defn dynamic-id [id params]
  [::dynamic id params])

(defn annotate-dynamic-reaction! [m id params]
  (update-in m [::rx-matcher id :instances] (fnil conj #{}) (dynamic-id id params)))

(defn reify-dynamic-reaction! [m id params]
  (let [dyn-id (dynamic-id id params)]
    (if (contains? m dyn-id)
      m
      (if-let [rx-gen (get (::rx-matcher m) id)]
        (let [rxn ((:fn rx-gen) dyn-id)
              upstream (:inputs rxn)]
          (-> (reduce
               (fn [m up]
                 (cond
                   (contains? m up)
                   m

                   (= ::dynamic (first up))
                   (reify-dynamic-reaction! m (nth up 1) (nth up 2))

                   :else
                   (throw (ex-info (str "No reaction found for id: " up)
                                   {:parent-rx (dynamic-id id params)
                                    :missing-rx up}))))
               m
               upstream)
              (add-reaction!
               rxn)
              (annotate-dynamic-reaction!
               id
               params)))
        (throw (ex-info (str "No dynamic reaction matches: " id)
                        {:id id}))))))

(defn infer-args-format
  ([m args]
   (cond
     (map? args) :map
     (keyword? args) :single
     (contains? m args) :single
     (vector? args) :vector
     :else (throw (ex-info "Unknown args style!" {:args args}))))
  ([m args default]
   (cond
     (map? args) :map
     (keyword? args) :single
     (contains? m args) :single
     (vector? args) :vector
     :else default)))

(defn args->inputs
  ([args-format args]
   (case args-format
     (:rx-map :vector)
     args

     :single
     [args]

     :map
     (vec (vals args)))))

(defn add-dynamic-reaction! [m rx-spec]
  (let [id (:id rx-spec)
        rx-gen {:fn (fn [[_ id params :as dyn-id]]
                      {:id dyn-id
                       :dynamic? true
                       ::params params
                       :args-format :rx-map
                       :args ((:inputs-fn rx-spec) dyn-id)
                       :inputs ((:inputs-fn rx-spec) dyn-id)
                       :fn (fn [input-map]
                             ((:rxn-fn rx-spec)
                              ((:args-fn rx-spec #(assoc %1 :params %2)) input-map params)))})}
        old-rxns (get-in m [::rx-matcher id :instances])]
    (update (apply dissoc m old-rxns) ::rx-matcher assoc id rx-gen)))

(defn find-reaction
  ([m id] (get m id))
  ([m id params]
   (if-some [cached (get m (dynamic-id id params))]
     cached
     (recur
      (reify-dynamic-reaction! m id params)
      id
      params))))

(defn compute-reaction
  ([m id]
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
  ([m id params]
   (compute-reaction (reify-dynamic-reaction! m id params) (dynamic-id id params))))

(defn compute-reaction!
  ([m id]
   (-> m
       (update id dissoc :value)
       (compute-reaction id)))
  ([m id params]
   (-> m
       (update [::dynamic id params] dissoc :value)
       (compute-reaction id params))))

(defn get-reaction
  ([m id]
   (if-let [rxn (get m id)]
     (if (contains? rxn :value)
       (:value rxn)
       (get-reaction (compute-reaction m id) id))
     (throw (ex-info (str "Reaction with id: " id " is not registered!")
                     {:id id}))))
  ([m id params]
   (get-reaction (compute-reaction m id params) (dynamic-id id params))))

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
   (add-reaction! m id ::root f {:args-format :single}))
  ([m id args f]
   (add-reaction! m id args f {}))
  ([m id args f opts]
   (let [args-format (or (:args-format opts)
                        (infer-args-format m args))
         inputs (args->inputs args-format args)]
     (-> m
         (annotate-inputs id inputs f)
         (assoc id (merge
                    opts
                    {:id id
                     :args-format args-format
                     :args args
                     :inputs inputs
                     :order-invariant
                     (inc
                      (reduce
                       (fnil max 0 0)
                       0
                       (map (comp :order-invariant (partial get m)) inputs)))
                     :parents (reduce into #{} (map (fn [i]
                                                      (conj
                                                       (:parents (get m i) #{})
                                                       i))
                                                    inputs))
                     :fn f}))
         (cond->
             (not (:lazy? opts)) (compute-reaction id))))))

(defn add-reactions! [reactive-map reactions]
  (let [get-inputs (fn [m rxn]
                     (args->inputs
                      (or (:args-format rxn)
                          (infer-args-format m (:args rxn) :single))
                      (:args rxn)))]
    (loop [m reactive-map
           blocked {}
           blocking {}
           [rxn :as rxns] reactions]
      (if (empty? rxns)
        (if (empty? blocked)
          m
          (throw (ex-info "Some reactions have inputs which don't exist!"
                          {:blocked blocked
                           :missing (keys blocking)})))
        (if-let [blocks (not-empty
                         (set
                          (remove
                           (partial contains? m)
                           (get-inputs m rxn))))]
          (recur m
                 (assoc blocked rxn blocks)
                 (reduce
                  (fn [acc in]
                    (update acc in (fnil conj #{}) rxn))
                  blocking
                  blocks)
                 (subvec rxns 1))
          (let [unblock (get blocking (:id rxn))
                [ready blocked] (reduce
                                 (fn [[racc bacc] r]
                                   (if-some [r-blocks (not-empty (disj (get bacc r) (:id rxn)))]
                                     [racc (assoc bacc r r-blocks)]
                                     [(conj racc r) (dissoc bacc r)]))
                                 [[] blocked]
                                 unblock)]
            (recur (add-reaction! m rxn)
                   blocked
                   (dissoc blocking (:id rxn))
                   (into ready (subvec rxns 1)))))))))

(defn clear-watchers [m trigger-id]
  (let [rxn (get m trigger-id)
        add-ids-by-invariant (partial reduce
                              (fn [acc id]
                                (update acc
                                        (:order-invariant
                                         (get m id)
                                         0)
                                        (fnil conj #{})
                                        id)))]
    (loop [m m
           triggered-by-order-invariant (add-ids-by-invariant (sorted-map) (:watchers rxn))]
      (if (empty? triggered-by-order-invariant)
        m
        (let [[k triggered] (first triggered-by-order-invariant)
              [m sorted-triggers]
              (reduce
               (fn [[m sorted-triggers] id]
                 (let [{:keys [lazy? value watchers] :as inner-rxn} (get m id)]
                   (if lazy?
                     [(-> m
                          (update id dissoc :value))
                      (add-ids-by-invariant sorted-triggers watchers)]
                     (let [m (compute-reaction! m id)]
                       (if (= (get-reaction m id) value)
                         [m sorted-triggers]
                         [m (add-ids-by-invariant sorted-triggers watchers)])))))
               [m (dissoc triggered-by-order-invariant k)]
               triggered)]
          (recur m sorted-triggers))))))

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
