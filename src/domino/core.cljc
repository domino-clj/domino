(ns domino.core
  (:require
    [domino.effects :as effects]
    [domino.events :as events]
    [domino.graph :as graph]
    [domino.model :as model]
    [domino.validation :as validation]
    [domino.util :as util]
    [domino.rx :as rx]))


(def eg-schema
  "Note, this would be generated from a more idiomatic/readable syntax, akin to the model declaration from domino."
  {:constraints
   [{:id :a-is-valid?
     :query {:a [:a]}
     :pred (fn [{:keys [a]}]
             (and (integer? a)
                  (odd? a)))}
    {:id :b-is-valid?
     :query {:b [:b]}
     :pred (fn [{:keys [b]}]
             (and (integer? b)))}
    {:id :compute-c
     :query {:a [:a]
             :b [:b]
             :c [:c]}
     :pred (fn [{:keys [a b c] :or {a 0 b 0 c 0}}]
             (= c (+ a b)))
     :resolver {:query {:a [:a] :b [:b]}
                :return {:c [:c]}
                :fn (fn [{:keys [a b]}]
                      {:c (+ a b)})}}]})

(def eg-reactions
  [[::db {:args ::rx/root
          :fn ::db}]
   [[:a] {:args ::db
          :fn #(:a % 0)}] ;; NOTE: could place defaults here
   [[:b] {:args ::db
          :fn #(:b % 0)}]
   [[:c] {:args ::db
          :fn #(:c % 0)}]
   [:pred/a-is-valid? {:args {:a [:a]}
                       :fn (fn [{:keys [a]}]
                             (and (integer? a)
                                  (even? a)))}]
   ;; TODO: include ID on value passed to inputs
   [:pred/b-is-valid? {:args {:b [:b]}
                       :fn (fn [{:keys [b]}]
                             (and (integer? b)))}]
   [:query/compute-c  {:args [[:a] [:b] [:c]]
                       :fn (fn [a b c]
                             {:a a
                              :b b
                              :c c})}]
   [:pred/compute-c {:args :query/compute-c
                     :fn (fn [{:keys [a b c]}]
                           (when-not (= c (+ a b))
                             :resolver/compute-c))}]
   [:resolver/compute-c {:args :query/compute-c ;; TODO: some other additional inputs from resolver
                         :lazy? true
                         :fn (fn [{:keys [a b]}]
                               ;; Compute changeset from resolver's return declaration
                               [[[:c] (+ a b)]])}]
   [:domino/conflicts? {:args [:pred/a-is-valid? :pred/b-is-valid? :pred/compute-c]
                        :fn (fn [& preds]
                              (not-empty
                               (filter
                                #(and (not (true? %)) (some? %))
                                preds)))}]])

(defn transact [state changes]
  (reduce
   (fn [s [path value]]
     (rx/update-root s update ::db assoc-in path value))
   state
   changes))

(defn initialize
  ([schema] (initialize schema {}))
  ([schema initial-db]
   ;; TODO: parse queries into reactions
   (let [state (reduce
                (fn [m [id rxn]]
                  (rx/add-reaction! m (assoc rxn :id id)))
                (rx/create-reactive-map {::db initial-db})
                schema)]
     (if-some [conflicts (rx/get-reaction state :domino/conflicts?)]
       (do
         ;; 1. find unresolvable conflicts and fail fast
         ;; 2. find all resolvers and run
         (let [resolver-id (some #(and (keyword? %) (= (namespace %) "resolver") %) conflicts)
               state (rx/compute-reaction state resolver-id)
               changes (rx/get-reaction state resolver-id)]
           (transact state changes)
           ;; TODO: loop recur until resolved
           )
         )))))

;; ==============================================================================
;; OLD VERSION
;; ==============================================================================

#?(:clj
   (comment
     (defmacro event [[_ in out :as args] & body]
       (let [in-ks#  (mapv keyword (:keys in))
             out-ks# (mapv keyword (:keys out))]
         {:inputs  in-ks#
          :outputs out-ks#
          :handler `(fn ~(vec args) ~@body)}))))
#_
(defn transact
  "Take the context and the changes which are an ordered collection of changes

  Assumes all changes are associative changes (i.e. vectors or hashmaps)"
  [ctx changes]
  (let [updated-ctx (events/execute-events ctx changes)]
    (effects/execute-effects! updated-ctx)
    updated-ctx))
#_
(defn initial-transaction
  "If initial-db is not empty, transact with initial db as changes"
  [{::keys [model] :as ctx} initial-db]
  (if (empty? initial-db)
    ctx
    (transact ctx
              (reduce
                (fn [inputs [_ path]]
                  (if-some [v (get-in initial-db path)]
                    (conj inputs [path v])
                    inputs))
                []
                (:id->path model)))))
#_
(defn initialize
  "Takes a schema of :model, :events, and :effects

  1. Parse the model
  2. Inject paths into events
  3. Generate the events graph
  4. Reset the local ctx and return value

  ctx is a map of:
    ::model => a map of model keys to paths
    ::events => a vector of events with pure functions that transform the state
    ::effects => a vector of effects with functions that produce external effects
    ::state => the state of actual working data
    "
  ([schema]
   (initialize schema {}))
  ([{:keys [model effects events] :as schema} initial-db]
   ;; Validate schema
   (validation/maybe-throw-exception (validation/validate-schema schema))
   ;; Construct ctx
   (let [model  (model/model->paths model)
         ;; TODO: Generate trivial events for all paths with `:pre` or `:post` and no event associated.
         events (model/connect-events model events)]
     (initial-transaction
       {::model         model
        ::events        events
        ::events-by-id  (util/map-by-id events)
        ::effects       (effects/effects-by-paths (model/connect-effects model effects))
        ::effects-by-id (util/map-by-id effects)
        ::db            initial-db
        ::graph         (graph/gen-ev-graph events)}
       initial-db))))
#_
(defn trigger-effects
  "Triggers effects by ids as opposed to data changes

  Accepts the context, and a collection of effect ids"
  [ctx effect-ids]
  (transact ctx (effects/effect-outputs-as-changes ctx effect-ids)))
