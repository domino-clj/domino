(ns datagrid.rules
  (:require
    [clojure.walk :refer [prewalk]]))

(defn cursor [path atom])

(def form
  {:model
   {:weight-lb {:model/path [:patient :weight :lb]}
    :weight-kg {:model/path [:patient :weight :kg]
                :model/validation []}
    :addresses  {:model/path [:user :addresses]}
    :bmi    {:model/path [:vitals :bmi]}
    :note   {:model/path [:note]}}

   :effects
   {:effect-name {:initial :state}
    :bmi-widget {:visible? false
                 :valid? true}}

   :model/effects
   [{:type :ui
     :inputs [:bmi]
     :targets [:bmi-widget]
     :handler (fn [ctx [bmi] [target]]
                [{:visible? true
                  :valid? false}])}]

   :model/events
   [{:type :collection
     :inputs  [:addresses]
     :actions  [:col/add :col/remove :col/move]}
    {:type :data
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


#_(def form
  {:model
   {:height-cm (r/atom)}
   :ui/events [(fn [ctx inputs outputs])]
   :model/events []})





;;todo add validation parsing
#_#_(defn init-model [form]
  (let [model (atom {})]
    (update form :model
            (fn [model]
              (into {}
                    (for [[id element] model]
                      [id (update element :model/path cursor model)]))))))

(defn init-events [form]
  (update form :events
          (fn [events]
            (for [{:keys [inputs outputs handler]} events]
              (fn [ctx]
                (handler
                  ctx
                  (map #(:model/path (get-in form [:model %])) inputs)
                  (map #(:model/path (get-in form [:model %])) outputs)))))))

