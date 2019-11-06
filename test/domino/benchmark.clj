(ns domino.benchmark
  (:require
    [domino.core :as core]
    [clojure.test :refer :all]
    [criterium.core :as criterium]))

(deftest ^:benchmark bench-initialize
  (criterium/bench
    (core/initialize!
      [{:inputs  [[:a]]
        :outputs [[:b] [:c]]
        :handler (fn [ctx [a] [b c]] [(+ a b) (+ a c)])}
       {:inputs  [[:c]]
        :outputs [[:d]]
        :handler (fn [ctx [c] _] [(inc c)])}])))

(deftest ^:benchmark bench-transact
  (let [ctx (core/initialize!
              [{:inputs  [[:a]]
                :outputs [[:b] [:c]]
                :handler (fn [ctx [a] [b c]] [(+ a b) (+ a c)])}
               {:inputs  [[:c]]
                :outputs [[:d]]
                :handler (fn [ctx [c] _] [(inc c)])}])]
    (criterium/bench (core/transact ctx [[[:a] 1] [[:b] 1]]))))


