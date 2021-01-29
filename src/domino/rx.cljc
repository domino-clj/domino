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
  (-get-value [this])
  (-stale? [this])
  (-mark-stale! [this]))

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
  (-stale? [this] false)
  (-mark-stale! [this])

  IRxResettable
  (-reset! [this new-v]
    (when (not= value new-v)
      (set! value new-v)
      (doseq [[_ watch-fn] watches]
        (when (fn? watch-fn)
          (watch-fn value new-v))))
    new-v))

(deftype RxReaction [inputs rx-fn ^:volatile-mutable watches ^:volatile-mutable value ^:volatile-mutable previous-fn-args ^:volatile-mutable stale?]
  IDominoReaction
  (-add-watch [this key f]
    (set! watches
          ((fnil assoc {}) watches key f)))
  (-upstream [this]
    (keys inputs))
  (-downstream [this]
    (keys watches))
  (-get-value [this]
    (if-not stale?
      value
      (let [args (into {}
                       (map
                        (fn [[id rxn]]
                          [id (-get-value rxn)]))
                       inputs)]
        #_(println "equal? " (= args previous-fn-args) " new " args " old "
                   previous-fn-args)
        (set! stale? false)
        (if (= args previous-fn-args)
          value
          (let [new-v (rx-fn args)]
            (set! previous-fn-args args)
            (when (not= value new-v)
              #_(println "Recomputed value" value "->" new-v)
              (set! value new-v)
              (doseq [[_ watch-fn] watches]
                (when (fn? watch-fn)
                  (watch-fn value new-v))))
            new-v)))))
  (-stale? [this] stale?)
  (-mark-stale! [this] (set! stale? true)))

(defprotocol IDominoRxMap
  (-get-reaction [this id]
    "Finds the value for the registered reaction. Should throw if unregistered.")
  #_(-get-value [this id]
    "Returns the specified root value. Should throw if unregistered.")
  (-reset-root! [this v])

  (-swap-root! [this f]
    [this f a]
    [this f a b]
    [this f a b c]
    [this f a b c d]
    [this f a b c d e]
    [this f a b c d e f]
    [this f a b c d e f args]))


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
    (doseq [rx (vals reactions)]
      (-mark-stale! rx))
    v)
  (-swap-root! [this f]
    (let [root (::root reactions)]
      (-reset-root! this (f (-get-value root)))))
  (-swap-root! [this f a]
    (let [root (::root reactions)]
      (-reset-root! this (f (-get-value root) a))))
  (-swap-root! [this f a b]
    (let [root (::root reactions)]
      (-reset-root! this (f (-get-value root) a b))))
  (-swap-root! [this f a b c]
    (let [root (::root reactions)]
      (-reset-root! this (f (-get-value root) a b c))))
  (-swap-root! [this f a b c d]
    (let [root (::root reactions)]
      (-reset-root! this (f (-get-value root) a b c d))))
  (-swap-root! [this f a b c d e]
    (let [root (::root reactions)]
      (-reset-root! this (f (-get-value root) a b c d e))))
  (-swap-root! [this fun a b c d e f]
    (let [root (::root reactions)]
      (-reset-root! this (fun (-get-value root) a b c d e f))))
  (-swap-root! [this fun a b c d e f args]
    (let [root (::root reactions)]
      (-reset-root! this (apply fun (-get-value root) a b c d e f args)))))

(defn update-root-impl
  ([^RxMap rx f]
   (-swap-root! rx f))
  ([^RxMap rx f a]
   (-swap-root! rx f a))
  ([^RxMap rx f a b]
   (-swap-root! rx f a b))
  ([^RxMap rx f a b c]
   (-swap-root! rx f a b c))
  ([^RxMap rx f a b c d]
   (-swap-root! rx f a b c d))
  ([^RxMap rx f a b c d e]
   (-swap-root! rx f a b c d e))
  ([^RxMap rx fun a b c d e f]
   (-swap-root! rx fun a b c d e f))
  ([^RxMap rx fun a b c d e f & args]
   (-swap-root! rx fun a b c d e f args)))

(defn update-root! [rx f & args]
  (apply update-root-impl rx f args)
  rx)

(defn add-reaction-impl [partial-rx-map {id     :id
                                         rx-fn  :fn
                                         inputs :inputs}]
  (let [watch-rxns (select-keys partial-rx-map inputs)
        new-rxn (->RxReaction
                 watch-rxns
                 rx-fn
                 {}
                 nil
                 nil
                 true)]
    (doseq [[watching rxn] watch-rxns]
      (-add-watch rxn id :annotation
                  #_(fn [_ _]
                    (println "id " id " being notified of change to " watching)
                    (-set-maybe-changed! new-rxn))))
    (assoc partial-rx-map id new-rxn)))

;; TODO: decide if :lazy? needs to be supported (or, conversely, if an `:eager?` option should be added.)

;; Initialization & Syntactic helpers

(defn infer-args-format
  "Expects a partial rx map and a args parameter from a reaction config.
   Based on structure of args and whether it exists in partial rx map it returns a keyword specifying the args type it expects.
   Optionally accepts a default type as a third argument."
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
  "Normalizes args to a canonical vector format.
   For use in determining input reaction IDs.
   Ensure that rx-fn is wrapped in a processing fn that will properly structure incoming args."
  ([args-format args]
   (case args-format
     (:rx-map :vector)
     args

     :single
     [args]

     :map
     (vec (vals args)))))


;; NOTE: Dynamic Construction

(defn create-reactive-map [values-map]
  (let [root (->RxRoot values-map {})]
    {::root root}))

(defn finalize-reactive-map [reactive-map]
  (let [rxmap
        (->RxMap reactive-map)]
    rxmap))

(defn- wrap-args [args-format args rx-fn]
  (case  args-format
    :single (fn [m]
              (rx-fn
               (get m args)))
    :rx-map rx-fn
    :map    (fn [m]
              (rx-fn
               (into {}
                     (map
                      (fn [[k id]]
                        [k (get m id)]))
                     args)))
    :vector (fn [m]
              (apply rx-fn
                     (mapv
                      (partial get m)
                      args)))
    (throw (ex-info "Unrecognized args-format!"
                    {:args-format args-format
                     :args        args}))))

(defn parse-rx-config
  "Transforms a reaction config map into a standard RxReaction creation map.
   Doesn't modify the `partial-rx-map` passed in, it is only used to match input reactions for args-format inferrence."
  [partial-rx-map rx-config]
  (let [args        (or (:args rx-config) (vec (:inputs rx-config)))
        args-format (:args-format rx-config
                                  (infer-args-format partial-rx-map args))
        inputs      (args->inputs args-format args)
        rx-fn       (wrap-args args-format args (:fn rx-config))]
    {:id     (:id rx-config)
     :inputs inputs
     :fn     rx-fn}))

(defn add-reaction! [reactive-map rx-config]
  ;; TODO: process reaction by wrapping rx-fn and returning the low-level rx-config-map
  (->> rx-config
       (parse-rx-config reactive-map)
       (add-reaction-impl reactive-map)))


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

(defn get-reaction [^RxMap rx-map rx-id]
  (-get-reaction rx-map rx-id))

(defn compute-reaction [rx-map _]
  #_(println "This function is deprecated. get-reaction will do neccessary computations only once.")
  rx-map)
