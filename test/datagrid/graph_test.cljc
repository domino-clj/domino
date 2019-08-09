(ns datagrid.graph-test
  (:require
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])
    [datagrid.graph :as graph]))

(def default-db {:a 0, :b 0, :c 0, :d 0, :e 0, :f 0, :g 0})

(defn test-graph-events
  ([events inputs expected-result]
   (test-graph-events default-db events inputs expected-result))
  ([db events inputs expected-result]
   (is
     (= expected-result
        (dissoc
          (graph/execute {} db (graph/gen-ev-graph events) inputs)
          ::graph/changed-paths
          ::graph/executed-events)))))

(deftest single-input-output
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx [a] [b]] [(+ a b)])}]
    [[[:a] 1]]
    {::graph/db      (assoc default-db :a 1 :b 1)
     ::graph/changes {[:a] 1 [:b] 1}}))

(deftest single-unchanged-input
  ;; todo might be better to not run any events if the inputs are the same as the current model
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx [a] [b]] [(inc a)])}]
    [[[:a] 0]]
    {::graph/db      (assoc default-db :b 1)
     ::graph/changes {[:a] 0 [:b] 1}}))

(deftest same-input-as-output
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:a]]
      :handler (fn [ctx [a] _] [(inc a)])}]
    [[[:a] 1]]
    {::graph/db      (assoc default-db :a 2)
     ::graph/changes {[:a] 2}}))

(deftest cyclic-inputs
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx [a] [b c]] [(inc b) c])}
     {:inputs  [[:b]]
      :outputs [[:a]]
      :handler (fn [ctx [b] [a]] [(inc a)])}]
    [[[:a] 1]]
    {::graph/db      (assoc default-db :b 1 :a 2)
     ::graph/changes {[:a] 2 [:b] 1}})
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx [a] [b c]] [(+ a b) c])}
     {:inputs  [[:b]]
      :outputs [[:a]]
      :handler (fn [ctx [b] [a]] [(+ b a)])}]
    [[[:a] 1] [[:b] 2]]
    {::graph/db      (assoc default-db :b 3 :a 4)
     ::graph/changes {[:a] 4 [:b] 3}}))

(deftest test-cascading-events
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx [a] [b c]] [(+ a b) (+ a c)])}
     {:inputs  [[:c]]
      :outputs [[:d]]
      :handler (fn [ctx [c] _] [(inc c)])}]
    [[[:a] 1] [[:b] 1]]
    {::graph/db      (assoc default-db :a 1 :b 2 :c 1 :d 2)
     ::graph/changes {[:a] 1 [:b] 2 [:c] 1 [:d] 2}}))

(deftest multi-input-event
  (test-graph-events
    [{:inputs  [[:a] [:b]]
      :outputs [[:c]]
      :handler (fn [ctx [a b] [c]] [(+ a b)])}]
    [[[:a] 1] [[:b] 1]]
    {::graph/db      (assoc default-db :a 1 :b 1 :c 2)
     ::graph/changes {[:a] 1 [:b] 1 [:c] 2}}))

(deftest multi-output-event
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx [a] [b c]] [(+ a b) (inc c)])}]
    [[[:a] 1]]
    {::graph/db      (assoc default-db :a 1 :b 1 :c 1)
     ::graph/changes {[:a] 1 [:b] 1 [:c] 1}}))

(deftest multi-input-output-event
  (test-graph-events
    [{:inputs  [[:a] [:b]]
      :outputs [[:b] [:c] [:d]]
      :handler (fn [ctx [a b] [_ c d]] [(+ a b) c d])}]
    [[[:a] 1] [[:b] 1]]
    {::graph/db      (assoc default-db :a 1 :b 2)
     ::graph/changes {[:a] 1 [:b] 2}}))
