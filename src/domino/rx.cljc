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
;; NOTE: a lot of the comments in this namespace are scratch notes or old.
;;       please disregard most or all of them.
;; ==============================================================================


;; ==============================================================================
;; NEW

(defprotocol IDominoReaction
  (-add-watch [this key f])
  (-upstream [this])
  (-downstream [this])
  (-get-value [this]))

(defprotocol IRxResettable
  (-reset! [this new-v]))

(deftype RxRoot [^:volatile-mutable value ^:volatile-mutable watches]
  IDominoReaction
  (-add-watch [this key f]
    (set! watches
          (assoc watches key f)))
  (-upstream [this]
    nil)
  (-downstream [this]
    (keys watches))
  (-get-value [this]
    value)

  IRxResettable
  (-reset! [this new-v]
    (when (not= value new-v)
      (set! value new-v)
      (doseq [[_ watch-fn] watches]
        (when (fn? watch-fn)
          (watch-fn value new-v))))
    new-v))

(deftype RxReaction [inputs rx-fn ^:volatile-mutable watches ^:volatile-mutable value ^:volatile-mutable previous-fn-args]
  IDominoReaction
  (-add-watch [this key f]
    (set! watches
          ((fnil assoc {}) watches key f)))
  (-upstream [this]
    (keys inputs))
  (-downstream [this]
    (keys watches))
  (-get-value [this]
    (let [args (map
                (fn [[_ rxn]]
                  (-get-value rxn))
                inputs)]
      (println "equal? " (= args previous-fn-args) " new " args " old "
               previous-fn-args)
      (if (= args previous-fn-args)
        value
        (let [new-v (apply rx-fn args)]
          (set! previous-fn-args args)
          (when (not= value new-v)
            (println "Recomputed value" value "->" new-v)
            (set! value new-v)
            (doseq [[_ watch-fn] watches]
              (when (fn? watch-fn)
                (watch-fn value new-v))))
          new-v)))))

(defprotocol IDominoRxMap
  (-get-reaction [this id]
    "Finds the value for the registered reaction. Should throw if unregistered.")
  #_(-get-value [this id]
    "Returns the specified root value. Should throw if unregistered.")
  (-reset-root! [this v])
  #_(-swap-value! [this id f]))


(deftype RxMap [reactions]
  IDominoRxMap
  (-get-reaction [this id]
    (if-some [rx (get reactions id)]
      (-get-value rx)
      (throw (ex-info "Reaction is not registered!"
                      {:reaction-id id}))))
  (-reset-root! [this v]
    (let [root (::root reactions)]
      (-reset! root v))
    v))

(defn add-reaction! [partial-rx-map {id     :id
                                     rx-fn  :fn
                                     inputs :inputs}]
  (let [watch-rxns (select-keys partial-rx-map inputs)
        _ (do
            (println (keys partial-rx-map))
            (println inputs)
            (println watch-rxns))
        new-rxn (->RxReaction
                 watch-rxns
                 rx-fn
                 {}
                 nil
                 true)]
    (doseq [[watching rxn] watch-rxns]
      (println "Adding watch to " watching  " for " id)
      (-add-watch rxn id :annotation
                  #_(fn [_ _]
                    (println "id " id " being notified of change to " watching)
                    (-set-maybe-changed! new-rxn))))
    (assoc partial-rx-map id new-rxn)))

(defn rx-map [values-map reactions]
  ;; TODO: Generate reactions for each of the values set.
  (let [root (->RxRoot values-map {})
        rx-map (reduce
                add-reaction!
                {::root root}
                reactions)]
    (->RxMap rx-map)))

;; NOTE: Dynamic Construction

(defn create-reactive-map [values-map]
  (let [root (->RxRoot values-map {})]
    {::root root}))

(defn get-reaction [& _])
(defn compute-reaction [& _])
(defn update-root [& _])
(defn args->inputs [& _])
(defn add-reactions! [& _])

;; TODO: Port order solution from `add-reactions!`


;; ==============================================================================
;; OLD
(comment
  ;; TODO: Enforce reaction shape to allow pattern matching for various operations!
  ;; Example 1.
  ;; Every patient has correlated imperial and metric heights, and is looked up by id.
  #_{:patients [{:id 1 :name "One" :height {:cm 180 :in 70.87}} {:id 2 :name "Two" :height {:cm 160 :in 62.99}}]}
  #_{::root
     {:value
      {:patients
       [{:id 1 :name "One" :height {:cm 180 :in 70.87}}
        {:id 2 :name "Two" :height {:cm 160 :in 62.99}}]}}
     {:type :path
      :static? true
      :path [:patients]
      :id :patients-literal}
     {:args ::root
      :fn (fn [db]
            (:patients db []))
      :value [{:id 1 :and :body} {:id 2 :and :body}]}

     ;; Some generated reaction for the indexes in patients
     {:type :indexes
      :collection :patients-literal
      :id :patients-indexes}
     {:args :patients-literal
      :args-format :single
      :fn (fn [patients]
            (map #(select-keys % [:id]) patients))
      :value '({:id 1} {:id 2})}
     ;; May want to add some generated constraint for uniqueness of indexes in patients

     ;; This is where it gets hard...

     ;; Now, we need a parametrized lookup
     {:type :path
      :static? false
      :parameters {:patient-id :p-id}
      :pattern [:patients {:id :patient-id}]}
     {... ?}}


  ;; NOTE: may want to refactor to allow optional params to a reaction (e.g. lookup)
  ;; NOTE: may want to look at reitit's approach of composing matchers or something?
  ;; TODO: Create example use case from domino.core for a collection, then
  ;;       backfill rx features.
  ;; NOTE: for collections, allow for transitive use of params, also allow for complete set of allowed params.


  (defn create-reactive-map [m]
    {::root {:value m
             :allow-set? true}
     ::rx-matcher {}})

  ;; Process for dynamic reactions:
  ;; 1. look for static impl.
  ;; 2. fallback to matcher and add static reification w/ watcher annotation. (annotate so that change => removal of reified rx)
  ;; 3. if match fails, error.


  ;; Reified-rx is of the form `[::dynamic <rx-id> <rx-params>]`
  ;; Reified-rx has an additional key `::params` on the body which is provided on add
  ;; Compute will reify parents

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

  ;; NOTE: I don't like this very much, but maybe it's okay?
  ;;       Let's play with it and see what we can do.
  ;;       May potentially change it so that *all* reactions have params, but some are static.


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
    ([m id] ;; TODO: update to clear only for passed params
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

  ;; TODO get-reaction with parameters!



  ;; TODO: get-upstream
  ;; TODO: get-downstream
  ;; TODO: print-graph
  ;; NOTE: Should we enforce `::root` as the single changeable point?
  ;;       (i.e. any other 'root' like things would be convenience fns around top-level keys)
  ;; TODO: allow for nested/dependent reactions. (i.e. one reaction for each element in a collection)


  (defn- annotate-inputs [m id inputs f] ;; TODO: does this need to change for dynamic queries?
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

  (defn add-reaction! ;; TODO: add description of parametrization
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

  (defn clear-watchers [m trigger-id] ;; TODO: clear watchers based on params associated with change.
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
                 ;; NOTE: we should clean up dynamic reactions which are stale somewhere.
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
    (set-value m id (apply f (get-reaction m id) args))))
