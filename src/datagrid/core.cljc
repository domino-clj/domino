(ns datagrid.core
  (:require
    [datagrid.effects :as effects]
    [datagrid.graph :as graph]
    [datagrid.model :as model]))

(defn initialize!
  "Takes a schema of :model/model, :model/effects, and :model/effects

  1. Parse the model
  2. Inject paths into events
  3. Generate the events graph
  4. Reset the local ctx and return value

  ctx is a map of:
    ::model => a map of model keys to paths
    ::events => ;; TODO
    ::effects => the side effects as configured in the schema passed in
    ::state => the state of actual working data
    "
  ([schema]
   (initialize! schema {}))
  ([{:model/keys [model effects events]} initial-db]
   (let [model (model/model->paths model)
         events (model/connect model events)]
     {::model   model
      ::events  events
      ::effects (effects/effects-by-paths (model/connect model effects))
      ::db      initial-db
      ::graph   (graph/gen-ev-graph events)})))

(defn transact
  "Take the context and the changes which are an ordered collection of changes

  Assumes all changes are associative changes (i.e. vectors or hashmaps)"
  [ctx changes]
  (let [updated-ctx (graph/execute-events ctx changes)]
    (effects/execute-effects! updated-ctx)
    updated-ctx))
