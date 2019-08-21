(ns datagrid.test-page
  (:require [datagrid.core :as core]
            [reagent.core :as r]
            [cljs.pprint :refer [pprint]]))

(defonce external-state (r/atom {}))

(defn gen-effects [{:model/keys [events] :as model}]
  (update model
          :model/effects
          (fnil into [])
          (reduce
            (fn [effects {:keys [outputs]}]
              (conj
                effects
                {:inputs  outputs
                 :handler (fn [_ output-values]
                            (swap! external-state merge (zipmap outputs output-values)))}))
            []
            events)))

(def ctx
  (atom
    (let [model {:model/model
                 [[:user {}
                   [:first-name {:id :fname}]
                   [:last-name {:id :lname}]
                   [:full-name {:id :full-name}]
                   [:weight {:id :weight}
                    [:lb {:id :lb}]
                    [:kg {:id :kg}]]]
                  [:physician {}
                   [:first-name {:id :physician-fname}]]]
                 :model/effects
                 []
                 :model/events
                 [{:inputs  [:fname :lname]
                   :outputs [:full-name]
                   :handler (fn [_ [fname lname] _]
                              [(or (when (and fname lname) (str lname ", " fname)) fname lname)])}
                  {:inputs  [:kg]
                   :outputs [:lb]
                   :handler (fn [_ [kg] _]
                              [(* kg 2.20462)])}
                  {:inputs  [:lb]
                   :outputs [:kg]
                   :handler (fn [_ [lb] _]
                              [(/ lb 2.20462)])}]}]
      (core/initialize! (gen-effects model) {}))))

(defn transact [path value]
  (swap! ctx core/transact [[path value]]))

(defn db-value [path]
  (get-in @ctx (into [:datagrid.core/db] path)))

(defn target-value [e]
  (.. e -target -value))

(defn input [label path & [fmt]]
  (r/with-let [local-state (r/atom nil)
               save-value  #(reset! local-state (if fmt (fmt (target-value %)) (target-value %)))]
    [:div
     [:label label " "]
     [:input
      {:value     @local-state
       :on-focus  #(reset! local-state (db-value path))
       :on-change save-value
       :on-blur   #(transact path @local-state)}]]))

(defn home-page []
  [:div
   [:pre (with-out-str (pprint (:datagrid.core/db @ctx)))]
   [:pre (with-out-str (pprint @external-state))]
   [input "First name" [:user :first-name]]
   [input "Last name" [:user :last-name]]
   [input "Weight (kg)" [:user :weight :kg] js/parseFloat]
   [input "Weight (lb)" [:user :weight :lb] js/parseFloat]
   [:label "Full name " (db-value [:user :full-name])]])

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (mount-root))
