(ns datagrid.test-page
  (:require [datagrid.core :as core]
            [reagent.core :as r]
            [cljs.pprint]))

(defonce state (r/atom {}))

(defonce ctx
         (atom
           (core/initialize! {:model/model   [[:user {:id :user}
                                               [:first-name {:id :fname}]
                                               [:last-name {:id :lname}]
                                               [:full-name {:id :full-name}]]]
                              :model/effects [{:inputs  [:fname :lname :full-name]
                                               :handler (fn [_ [fname lname full-name]]
                                                          (swap! state assoc
                                                                 :first-name fname
                                                                 :last-name lname
                                                                 :full-name full-name))}]
                              :model/events  [{:inputs  [:fname :lname]
                                               :outputs [:full-name]
                                               :handler (fn [_ [fname lname] _]
                                                          [(or (when (and fname lname) (str lname ", " fname)) fname lname)])}]}
                             {})))

(defn transact [path value]
  (swap! ctx core/transact [[path value]]))

(defn target-value [e]
  (.. e -target -value))

(defn home-page []
  [:div
   [:p "First name"]
   [:input
    {:on-change #(transact [:user :first-name] (target-value %))}]
   [:p "Last name"]
   [:input
    {:on-change #(transact [:user :last-name] (target-value %))}]
   [:p "Full name"]
   [:p (:full-name @state)]])

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (mount-root))

