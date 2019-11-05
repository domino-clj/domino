(ns domino.core
  (:require
    [domino.effects :as effects]
    [domino.graph :as graph]
    [domino.model :as model]))

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
  ([{:keys [model effects events]} initial-db]
   (let [model  (model/model->paths model)
         events (model/connect model events)]
     {::model        model
      ::events       events
      ::events-by-id (reduce
                       (fn [events-by-id event]
                         (if-let [id (:id event)]
                           (assoc events-by-id id event)
                           events-by-id))
                       events)
      ::effects      (effects/effects-by-paths (model/connect model effects))
      ::db           initial-db
      ::graph        (graph/gen-ev-graph events)})))

(defn transact
  "Take the context and the changes which are an ordered collection of changes

  Assumes all changes are associative changes (i.e. vectors or hashmaps)"
  [ctx changes]
  (let [updated-ctx (graph/execute-events ctx changes)]
    (effects/execute-effects! updated-ctx)
    updated-ctx))
