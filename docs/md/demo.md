# Example of using Domino with Reagent

<pre><code class="language-eval-clojure" data-external-libs="https://raw.githubusercontent.com/domino-clj/domino/master/src">
(require '[domino.core :as core])
(require '[reagent.core :as reagent])
(require '[cljs.pprint :refer [pprint]])

(def ctx
  (reagent/atom
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
                   :handler (fn [_ [full-name]]
                              (when (= "Bobberton, Bob" full-name)
                                (js/alert "launching missiles!")))}]
                 :events
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
      (core/initialize! model {}))))

(defn transact [path value]
  (swap! ctx core/transact [[path value]]))

(defn db-value [path]
  (get-in @ctx (into [::core/db] path)))

(defn target-value [e]
  (.. e -target -value))

(defn state-atom [path]
  (let [state (reagent/atom nil)]
    (add-watch ctx path
               (fn [path _ old-state new-state]
                 (let [ctx-path (into [::core/db] path)
                       old-value (get-in old-state ctx-path)
                       new-value (get-in new-state ctx-path)]
                   (when (not= old-value new-value)
                     (reset! state new-value)))))
    state))


 (defn input [label path & [fmt]]
   (let [local-state (state-atom path)
         save-value  #(reset! local-state (if fmt (fmt (target-value %)) (target-value %)))]
     (fn []
       [:div.field.is-horizontal
        [:label.field-label.is-normal label " "]
        [:div.field-body>input.input
         {:value     @local-state
          :on-change save-value
          :on-blur   #(transact path @local-state)}]])))

(defn home-page []
  [:div
   [input "First name" [:user :first-name]]
   [input "Last name" [:user :last-name]]
   [input "Weight (kg)" [:user :weight :kg] (fnil js/parseFloat 0)]
   [input "Weight (lb)" [:user :weight :lb] (fnil js/parseFloat 0)]
   [:label "Full name " (db-value [:user :full-name])]
   [:hr]
   [:h4 "DB state"]
   [:pre (with-out-str (pprint (:domino.core/db @ctx)))]])

(reagent/render-component [home-page] js/klipse-container)
</code></pre>
