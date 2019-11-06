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
                    :handler (fn [_ {:keys [fname lname full-name]}]
                               (swap! state assoc
                                      :first-name fname
                                      :last-name lname
                                      :full-name full-name))}]
         :events  [{:inputs  [:fname :lname]
                    :outputs [:full-name]
                    :handler (fn [_ {:keys [fname lname]} _]
                               {:full-name (or (when (and fname lname) (str lname ", " fname))
                                               fname
                                               lname)})}]}))))

(deftest ^:benchmark bench-transact
  (let [ctx (core/initialize
              {:model   [[:f {:id :f}
                          [:a {:id :a}]
                          [:b {:id :b}]
                          [:c {:id :c}]
                          [:d {:id :d}]]]
               :events  [{:inputs  [:a :b]
                          :outputs [:c]
                          :handler (fn [_ {:keys [a b]} _]
                                     {:c "C"})}
                         {:inputs  [:b :c]
                          :outputs [:d]
                          :handler (fn [_ {:keys [b c]} _]
                                     {:d "D"})}
                         {:inputs  [:d]
                          :outputs [:a]
                          :handler (fn [_ {:keys [d]} _]
                                     {:a "A"})}]})]
    (criterium/bench (core/transact ctx [[[:a] 1] [[:b] 1]]))))
