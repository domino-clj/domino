(ns domino.core
  (:require
    [domino.effects :as effects]
    [domino.events :as events]
    [domino.graph :as graph]
    [domino.model :as model]
    [domino.validation :as validation]
    [domino.util :as util]))

(defn transact
  "Take the context and the changes which are an ordered collection of changes

  Assumes all changes are associative changes (i.e. vectors or hashmaps)"
  [ctx changes]
  (let [updated-ctx (events/execute-events ctx changes)]
    (effects/execute-effects! updated-ctx)
    updated-ctx))

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

(defn trigger-events
  "Triggers events by ids as opposed to data changes

  Accepts the context, and a collection of event ids"
  [ctx event-ids]
  (transact ctx (events/events-inputs-as-changes ctx event-ids)))

(defn trigger-effects
  "Triggers effects by ids as opposed to data changes

  Accepts the context, and a collection of effect ids"
  [ctx effect-ids]
  (transact ctx (effects/effect-outputs-as-changes ctx effect-ids)))
