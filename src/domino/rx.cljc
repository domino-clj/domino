(ns domino.rx
  (:require [clojure.set :refer [union]]))

;; ==============================================================================
;; ABOUT
;; this namespace provides a reactive-map abstraction based on reagent/re-frame.
;; Instead of using state, we expose a means of registering reactions along with
;; an update function. The update function triggers recomputation of registered
;; reactions from changed pieces of state. It will run any reactions with changed
;; inputs exactly once.
;;
;; ==============================================================================

;; NOTE: may want to refactor to allow optional params to a reaction (e.g. lookup)
;; NOTE: may want to look at reitit's approach of composing matchers or something?
;; TODO: Create example use case from domino.core for a collection, then
;;       backfill rx features.


(defn create-reactive-map [m]
  {::root {:value m
           :allow-set? true}})


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
        compute (comp (fn [r] (println "[DEBUG]" "Computing reaction:")
                        (clojure.pprint/pprint r)
                        r)
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
       (println "[WARN]" "Value isn't computed. Run compute-reaction to update your reactive map and cache the computation!")
       (get-reaction (compute-reaction m id) id)))
    (throw (ex-info (str "Reaction with id: " id " is not registered!")
                    {:id id}))))


;; TODO: get-upstream
;; TODO: get-downstream
;; TODO: print-graph
;; NOTE: Should we enforce `::root` as the single changeable point?
;;       (i.e. any other 'root' like things would be convenience fns around top-level keys)
;; TODO: allow for nested/dependent reactions. (i.e. one reaction for each element in a collection)

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
                     ;; Order invariant is for computation of inputs. It ensures that new computations are resolved in proper order.
                     ;; It is the largest of the order invariants of it's inputs, plus one.
                     ;; This means that if all triggered changes with lower order invariant have been computed,
                     ;;   then all triggered parents must've been computed.
                     ;; Additionally, any changes triggered must have a higher order invariant.
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
                     ;; NOTE: since infer-args-format is based on a complete map, we must set a default
                     (args->inputs
                      (or (:args-format rxn)
                          (infer-args-format m (:args rxn) :single))
                      (:args rxn)))]
    (loop [m reactive-map
           blocked {}
           blocking {}
           [rxn :as rxns] reactions]
      (println "[DEBUG]" "attempting to add: " (:id rxn))
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
          (do
            (println "[DEBUG]" "BLOCKED BY: " blocks)
            (recur m
                   (assoc blocked rxn blocks)
                   (reduce
                    (fn [acc in]
                      (update acc in (fnil conj #{}) rxn))
                    blocking
                    blocks)
                   (subvec rxns 1)))
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
      (println "ID: " trigger-id)
      (println "TRIGGERED: " triggered-by-order-invariant)
      (if (empty? triggered-by-order-invariant)
        m
        (let [[k triggered] (first triggered-by-order-invariant)
              _ (println "Handling invariant: " k)
              [m sorted-triggers]
              (reduce
               ;; NOTE: this is re-running reactions for each input that changes
               ;;       we should wait for all inputs to resolve, and then resume.
               (fn [[m sorted-triggers] id]
                 (let [{:keys [lazy? value watchers] :as inner-rxn} (get m id)]
                   (println "Handling ID: " id "lazy? " lazy? "watchers: " (add-ids-by-invariant sorted-triggers watchers))
                   (if lazy?
                     [(-> m
                          (update id dissoc :value))
                      (add-ids-by-invariant sorted-triggers watchers)]
                     (let [m (compute-reaction! m id)]
                       (if (= (get-reaction m id) value)
                         (do
                           (println "ID:" id " unchanged.")
                           [m sorted-triggers])
                         (do
                           (println "ID: " id "was changed!")
                           (println "Value of ID:" id "\n" value "\n->\n" (get-reaction m id))
                           [m (add-ids-by-invariant sorted-triggers watchers)]))))))
               [m (dissoc triggered-by-order-invariant k)]
               triggered)]
          (recur m sorted-triggers))))
    #_
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
