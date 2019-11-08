# Example of Domino with re-frame

<pre><code class="language-clojure lang-eval-clojure" data-external-libs="https://raw.githubusercontent.com/domino-clj/domino/master/src,https://raw.githubusercontent.com/day8/re-frame/tree/master/src">
(require '[domino.core :as domino])
</code></pre>

```clojure lang-eval-clojure
(require '[reagent.core :as reagent])
(require '[re-frame.core :as rf])
(require '[goog.string :as string])
(require '[goog.string.format])
(require '[cljs.pprint :refer [pprint]])
```

```clojure lang-eval-clojure
(rf/reg-event-db
 :init
 (fn [_ [_ schema]]
   (domino/initialize schema)))

(rf/reg-event-db
 :event
 (fn [db [_ id value]]
   (domino/transact db [[(get-in (::domino/model db) [:id->path id]) value]])))

(rf/reg-sub
 :id
 (fn [db [_ id]]
   (get-in (::domino/db db) (get-in (::domino/model db) [:id->path id]))))

(rf/reg-sub
 :db
 (fn [db _]
   (::domino/db db)))

(defn parse-float [s]
  (let [value (js/parseFloat s)]
    (when-not (js/isNaN value) value)))

(defn format-number [n]
  (when n (string/format "%.2f" n)))

(defn text-input [label id]
  [:div
   [:label label]
   [:input
    {:type      :text
     :value @(rf/subscribe [:id id])
     :on-change #(rf/dispatch [:event id (-> % .-target .-value)])}]])

(defn numeric-input [label id]
  [:div
   [:label label]
   [:input 
    {:type      :text
     :value @(rf/subscribe [:id id])
     :on-change #(rf/dispatch [:event id (-> % .-target .-value parse-float)])}]])

(rf/dispatch-sync
   [:init
    {:model
     [[:demographics
       [:first-name {:id :first-name}]
       [:last-name {:id :last-name}]
       [:full-name {:id :full-name}]]
      [:vitals
       [:height {:id :height}]
       [:weight {:id :weight}]
       [:bmi {:id :bmi}]]]
     :effects
     [{:inputs  [:full-name]
       :handler (fn [_ {:keys [full-name]}]
                  (when (= "Bobberton, Bob" full-name)
                    (js/alert "Hi Bob!")))}]
     :events
     [{:inputs  [:first-name :last-name]
       :outputs [:full-name]
       :handler (fn [_ {:keys [first-name last-name]} _]
                  {:full-name (or (when (and first-name last-name)
                                    (str last-name ", " first-name))
                                  first-name
                                  last-name)})}
      {:inputs  [:height :weight]
       :outputs [:bmi]
       :handler (fn [_ {:keys [height weight]} {:keys [bmi]}]
                  {:bmi (if (and height weight)
                          (/ weight (* height height))
                          bmi)})}]}])

(defn home-page []
  [:div
   [:h3 "Patient demographics"]
   [text-input "First name" :first-name]
   [text-input "Last name" :last-name]
   [numeric-input "Height (M)" :height (fnil js/parseFloat 0)]
   [numeric-input "Weight (KG)" :weight (fnil js/parseFloat 0)]
   [:p>label "Full name " @(rf/subscribe [:id :full-name])]
   [:p>label "BMI " (format-number @(rf/subscribe [:id :bmi]))]
   [:hr]
   [:h4 "DB state"]
   [:pre (with-out-str (pprint @(rf/subscribe [:db])))]])

(reagent/render-component [home-page] js/klipse-container)
```
