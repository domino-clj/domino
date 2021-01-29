(ns domino.benchmark
  (:require
    [domino.core :as core]
    [clojure.test :refer :all]
    [criterium.core :as criterium]))

(deftest ^:benchmark bench-initialize
  (println "START INIT")
  (let [state (atom {})]
    (-> (core/initialize
         {:model   [[:user {:id :user}
                     [:first-name {:id :fname}]
                     [:last-name {:id :lname}]
                     [:full-name {:id :full-name}]]]
          :effects [{:inputs  [:fname :lname :full-name]
                     :handler (fn [{{:keys [fname lname full-name]} :inputs}]
                                (swap! state assoc
                                       :first-name fname
                                       :last-name lname
                                       :full-name full-name))}]
          :events  [{:inputs  [:fname :lname]
                     :outputs [:full-name]
                     :handler (fn [{{:keys [fname lname]} :inputs}]
                                {:full-name (or (when (and fname lname) (str lname ", " fname))
                                                fname
                                                lname)})}]})
        (criterium/quick-bench))
    (println "FINISH INIT")))

(deftest ^:benchmark bench-transact-events
  (println "START EVENTS")
  (let [ctx (core/initialize
              {:model   [[:f {:id :f}
                          [:a {:id :a}]
                          [:b {:id :b}]
                          [:c {:id :c}]
                          [:d {:id :d}]]]
               :events  [{:inputs  [:a :b]
                          :outputs [:c]
                          :handler (fn [{{:keys [a b]} :inputs}]
                                     {:c "C"})}
                         {:inputs  [:b :c]
                          :outputs [:d]
                          :handler (fn [{{:keys [b c]} :inputs}]
                                     {:d "D"})}
                         {:inputs  [:d]
                          :outputs [:a]
                          :handler (fn [{{:keys [d]} :inputs}]
                                     {:a "A"})}]})]
    (->
     (core/transact ctx [[::core/set-value :a 1]
                         #_[::core/set-value :b 1]])
     (criterium/quick-bench))
    (println "FINISH EVENTS")))


(deftest ^:benchmark bench-transact-no-events
  (println "START NO EVENTS")
  (let [ctx (core/initialize
             {:model   [[:g {:id :g}]
                        [:f {:id :f}
                         [:a {:id :a}]
                         [:b {:id :b}]
                         [:c {:id :c}]
                         [:d {:id :d}]]]
              :events  [{:inputs  [:a :b]
                         :outputs [:c]
                         :handler (fn [{{:keys [a b]} :inputs}]
                                    {:c "C"})}
                        {:inputs  [:b :c]
                         :outputs [:d]
                         :handler (fn [{{:keys [b c]} :inputs}]
                                    {:d "D"})}
                        {:inputs  [:d]
                         :outputs [:a]
                         :handler (fn [{{:keys [d]} :inputs}]
                                    {:a "A"})}]})]
    (->
     (core/transact ctx [[::core/set-value :g 1]])
     (criterium/quick-bench))
    (println "FINISH NO EVENTS")))
