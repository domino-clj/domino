(ns domino.benchmark
  (:require
    [domino.core :as core]
    [clojure.test :refer :all]
    [criterium.core :as criterium]))

(deftest ^:benchmark bench-initialize
  (let [state (atom {})]
    (criterium/bench
      (core/initialize
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
                                               lname)})}]}))))

(deftest ^:benchmark bench-transact-events
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
    (criterium/bench (core/transact ctx [[::core/set-value :a 1] [::core/set-value :b 1]]))))


(deftest ^:benchmark bench-transact-no-events
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
    (criterium/bench (core/transact ctx [[::core/set-value :g 2]]))))
