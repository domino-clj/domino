(ns datagrid.core
  (:require [datagrid.effects :as effects]
            [datagrid.events :as events]
            [datagrid.graph :as graph]
            [datagrid.model :as model]
            [datagrid.reactive-atom :as ratom]))

;; ctx =>
#_{::model  ..
 ::events ..
 ::effects ..}

(defn initialize
  "
  1. Parse the model
  2. Inject paths into events
  3. Generate the events graph
  4. Return initial ctx"
  ([schema]
   (initialize schema (ratom/atom {})))
  ([schema data-store]
    ))