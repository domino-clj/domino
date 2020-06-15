(ns domino.test-page
  (:require [domino.core :as core]
            [reagent.core :as r]
            [clojure.string :as string]
            [cljs.pprint :refer [pprint]]))

(def ctx
  (r/atom
    (let [model {:model
                 [[:user {}
                   [:first-name {:id :fname}]
                   [:last-name {:id :lname}]
                   [:full-name {:id :full-name}]
                   [:weight {:id :weight}
                    [:lb {:id :lb}]
                    [:kg {:id :kg}]]]
                  [:physician {}
                   [:first-name {:id :physician-fname}]]]
                 :effects
                 [{:inputs  [:full-name]
                   :handler (fn [{{:keys [full-name]} :inputs}]
                              (when (= "Bobberton, Bob" full-name)
                                (js/alert "launching missiles!")))}]
                 :events
                 [{:inputs  [:fname :lname]
                   :outputs [:full-name]
                   :handler (fn [{{:keys [fname lname]} :inputs}]
                              {:full-name
                               (string/join
                                ", "
                                (keep
                                 (comp not-empty string/trim)
                                 [lname fname]))})}
                  {:inputs  [:kg]
                   :outputs [:lb]
                   :handler (fn [{{:keys [kg]} :inputs}]
                              {:lb ((fnil * 0) kg 2.20462)})}
                  {:inputs  [:lb]
                   :outputs [:kg]
                   :handler (fn [{{:keys [lb]} :inputs}]
                              {:kg ((fnil / 0) lb 2.20462)})}]}]

      (core/initialize model {}))))

(defn transact [id value]
  ;; NOTE: transact without a cb will *ONLY* work with synchronous events.
  (swap! ctx core/transact [{id value}]))

(defn db-value [path]
  (get-in @ctx (into [::core/db] path)))

(defn target-value [e]
  (.. e -target -value))

(defn state-atom [id]
  (let [state (r/atom nil)]
    (add-watch ctx id
               (fn [id _ old-state new-state]
                 (let [ctx-path (into [::core/db] (get (::core/id->path old-state) id))
                       old-value (get-in old-state ctx-path)
                       new-value (get-in new-state ctx-path)]
                   (when (not= old-value new-value)
                     (reset! state new-value)))))
    state))

(defn input [label id & [fmt]]
  (r/with-let [local-state (state-atom id)
               save-value  #(reset! local-state (if fmt (fmt (target-value %)) (target-value %)))]
    [:div
     [:label label " "]
     [:input
      {:value     @local-state
       :on-change save-value
       :on-blur   #(transact id @local-state)}]]))

(defn home-page []
  [:div
   [input "First name" :fname]
   [input "Last name" :lname]
   [input "Weight (kg)" :kg (comp (fnil js/parseFloat 0) not-empty (fnil string/trim ""))]
   [input "Weight (lb)" :lb (comp (fnil js/parseFloat 0) not-empty (fnil string/trim ""))]
   [:label "Full name " (db-value [:user :full-name])]
   [:hr]
   [:h4 "DB state"]
   [:pre (with-out-str (pprint (:domino.core/db @ctx)))]
   [:pre (with-out-str (pprint (:domino.core/transaction-report @ctx)))]])

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (mount-root))
