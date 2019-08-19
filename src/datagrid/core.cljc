(ns datagrid.core
  (:require
    [datagrid.graph :as graph]
    [datagrid.effects :as effects]
    [datagrid.model :as model]
    [datagrid.reactive-atom :as ratom]))

;; TODO: figure this out later
(defonce ctx (atom {}))

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
     (reset! ctx
             {::model   model
              ::events  events
              ::effects (effects/effects-by-paths (model/connect model effects))
              ::db      initial-db
              ::graph   (graph/gen-ev-graph events)}))))

#_(execute-effects!
    {:changes  {[:a] 1 [:b] 1}
     ::effects (effects/effects-by-paths [{:inputs [[:a]] :handler (fn [ctx inputs]
                                                                     (prn inputs))}])})

(defn pub
  "Take the changes, an ordered collection of changes

  Assumes all changes are associative changes (i.e. vectors or hashmaps)"
  ([changes]
   (pub ctx changes))
  ([ctx changes]
   (let [updated-ctx (swap! ctx graph/execute-events changes)]
     ;#?(:cljs (cljs.pprint/pprint @ctx))
     (effects/execute-effects! updated-ctx)
     updated-ctx)))

;; dereffable
(defn sub
  ([k]
   (sub ctx k))
  ([ctx k]
   ))
