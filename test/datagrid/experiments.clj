(ns datagrid.experiments
  (:require [clojure.test :refer :all]))

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

