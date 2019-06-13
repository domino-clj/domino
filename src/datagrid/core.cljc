(ns datagrid.core
  (:require [datagrid.effects :as effects]
            [datagrid.events :as events]
            [datagrid.graph :as graph]
            [datagrid.model :as model]
            [datagrid.reactive-atom :as ratom]))

(defonce ctx (atom {}))

(def example-schema
  {:model/model
   [[:patient {}
     [:weight {}
      [:lb {:id :weight-lb}]
      [:kg {:id :weight-kg}]]
     [:vitals {}
      [:bmi {:id :bmi}]]
     [:note {:id :note}]
     [:addresses {:id :addresses}]]]

   :model/effects
   {:model
    {:effect-name {:initial :state}
     :bmi-widget  {:visible? false
                   :valid?   true}}
    :effects
    [{:type    :ui
      :inputs  [:bmi]
      :targets [:bmi-widget]
      :handler (fn [ctx [bmi] [target]]
                 [{:visible? true
                   :valid?   false}])}]}

   :model/events
   [{:type    :collection
     :inputs  [:addresses]
     :actions [:col/add :col/remove :col/move]}
    {:type    :data
     :inputs  [:weight-lb]
     :outputs [:weight-kg]
     :handler (fn [ctx [height wieght] [bmi]])}
    {:inputs  [:weight-lb]
     :outputs [:weight-kg]
     :handler (fn [ctx [height wieght] [bmi]])}
    {:inputs  [:bmi]
     :outputs [:note]
     :handler (fn [ctx [bmi] [note]])}
    {:inputs  [:items]
     :outputs [:note]
     :handler (fn [ctx #_[[[:items 0 :value] "value"]] [note]])}]})

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