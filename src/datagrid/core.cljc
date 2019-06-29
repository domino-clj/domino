(ns datagrid.core
  (:require [datagrid.effects :as effects]
            [datagrid.events :as events]
            [datagrid.graph :as graph]
            [datagrid.model :as model]
            [datagrid.reactive-atom :as ratom]))

(defn initialize!
  "Takes a schema of :model/model, :model/effects, and :model/effects

  1. Parse the model
  2. Inject paths into events
  3. Generate the events graph
  4. Reset the local ctx and return value

  ctx is a map of:
    ::model => a map of model keys to paths
    ::events =>
    ::effects => the side effects as configured in the schema passed in
    ::state => the state of actual working data
    "
  ([schema]
   (initialize! schema (ratom/atom {})))
  ([{:model/keys [model effects events]} data-store]
   (reset! ctx
           {::model   (model/model->paths model)
            ::events  events                                ;; todo #3
            ::effects effects
            ::state   data-store})))

(defn update-state
  [ctx [k v]]
  (assoc-in ctx (cons ::state (seq k)) v))

;; This might need to be updated given changes as an ordered collection
(defn apply-changes!
  [ctx changes]
  (doseq [change changes]
    (swap! ctx update-state change)))

;; example of changes
[{[:patient :name] "bob"}
 {:type  :col/add
  :path  [:patient :addresses]
  :value "123 hello"}]

(defn pub
  "Take the changes, an ordered collection of changes

  Assumes all changes are associative changes (i.e. vectors or hashmaps)"
  ([changes]
   (pub ctx changes))
  ([ctx changes]
   (-> ctx
       (apply-changes! changes)
       (events/on-model-update changes)
       (effects/on-model-update! changes))))

;; dereffable
(defn sub
  ([k]
   (sub ctx k))
  ([ctx k]
    ))
