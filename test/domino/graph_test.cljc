(ns domino.graph-test
  (:require
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])
    [domino.graph :as graph]
    [domino.core :as core]))

(def default-db {:a 0, :b 0, :c 0, :d 0, :e 0, :f 0, :g 0 :h {:i 0}})

(defn test-graph-events
  ([events inputs expected-result]
   (test-graph-events default-db events inputs expected-result))
  ([db events inputs expected-result]
   (test-graph-events {} db events inputs expected-result))
  ([ctx db events inputs expected-result]
   (is
     (= expected-result
        (-> (merge
              ctx
              {::core/db    db
               ::core/graph (graph/gen-ev-graph events)})
            (graph/execute-events inputs)
            (select-keys [::core/db :change-history]))))))

(deftest no-events
  (test-graph-events
    []
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1)
     :change-history [[[:a] 1]]}))

(deftest nil-output-ignored
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [_ _ _])}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1)
     :change-history [[[:a] 1]]}))

(deftest single-input-output
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx [a] [b]] [(+ a b)])}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1 :b 1)
     :change-history [[[:a] 1] [[:b] 1]]}))

(deftest unmatched-event
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx _ _] [5])}]
    [[[:c] 1]]
    {::core/db       (assoc default-db :c 1)
     :change-history [[[:c] 1]]}))

(deftest nil-value
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [_ _ _] [nil])}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1 :b nil)
     :change-history [[[:a] 1] [[:b] nil]]}))

(deftest exception-bubbles-up
  (is
    (thrown?
      #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
      (graph/execute-events
        {::core/db    default-db
         ::core/graph (graph/gen-ev-graph
                        [{:inputs  [[:a]]
                          :outputs [[:b]]
                          :handler (fn [ctx [a] [b]] (throw (ex-info "test" {:test :error})))}])}
        [[[:a] 1]]))))

(deftest single-unchanged-input
  ;; todo might be better to not run any events if the inputs are the same as the current model
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx [a] [b]] [(inc a)])}]
    [[[:a] 0]]
    {::core/db       (assoc default-db :b 1)
     :change-history [[[:a] 0] [[:b] 1]]}))

(deftest same-input-as-output
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:a]]
      :handler (fn [ctx [a] _] [(inc a)])}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 2)
     :change-history [[[:a] 1]
                      [[:a] 2]]}))

(deftest cyclic-inputs
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx [a] [b c]] [(inc b) c])}
     {:inputs  [[:b]]
      :outputs [[:a]]
      :handler (fn [ctx [b] [a]] [(inc a)])}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :b 1 :a 2)
     :change-history [[[:a] 1]
                      [[:b] 1]
                      [[:a] 2]]})
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx [a] [b c]] [(+ a b) c])}
     {:inputs  [[:b]]
      :outputs [[:a]]
      :handler (fn [ctx [b] [a]] [(+ b a)])}]
    [[[:a] 1] [[:b] 2]]
    {::core/db       (assoc default-db :b 3 :a 4)
     :change-history [[[:a] 1]
                      [[:b] 2]
                      [[:b] 3]
                      [[:a] 4]]}))

(deftest test-cascading-events
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx [a] [b c]] [(+ a b) (+ a c)])}
     {:inputs  [[:c]]
      :outputs [[:d]]
      :handler (fn [ctx [c] _] [(inc c)])}]
    [[[:a] 1] [[:b] 1]]
    {::core/db       (assoc default-db :a 1 :b 2 :c 1 :d 2)
     :change-history [[[:a] 1]
                      [[:b] 1]
                      [[:b] 2]
                      [[:c] 1]
                      [[:d] 2]]}))

(deftest multi-input-event
  (test-graph-events
    [{:inputs  [[:a] [:b]]
      :outputs [[:c]]
      :handler (fn [ctx [a b] [c]] [(+ a b)])}]
    [[[:a] 1] [[:b] 1]]
    {::core/db       (assoc default-db :a 1 :b 1 :c 2)
     :change-history [[[:a] 1] [[:b] 1] [[:c] 2]]}))

(deftest multi-output-event
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx [a] [b c]] [(+ a b) (inc c)])}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1 :b 1 :c 1)
     :change-history [[[:a] 1] [[:b] 1] [[:c] 1]]}))

(deftest multi-input-output-event
  (test-graph-events
    [{:inputs  [[:a] [:b]]
      :outputs [[:b] [:c] [:d]]
      :handler (fn [ctx [a b] [_ c d]] [(+ a b) c d])}]
    [[[:a] 1] [[:b] 1]]
    {::core/db       (assoc default-db :a 1 :b 2)
     :change-history [[[:a] 1]
                      [[:b] 1]
                      [[:b] 2]]}))

(deftest unrelated-events
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx [a] _] [(inc a)])}
     {:inputs  [[:c]]
      :outputs [[:d]]
      :handler (fn [ctx [c] _] [(dec c)])}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1 :b 2)
     :change-history [[[:a] 1] [[:b] 2]]}))

(deftest context-access
  (test-graph-events
    {:action #(+ % 5)}
    default-db
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx [a] _] [((:action ctx) a)])}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1 :b 6)
     :change-history [[[:a] 1] [[:b] 6]]}))

(deftest triggering-sub-path
  (test-graph-events
    [{:inputs  [[:h]]
      :outputs [[:h]]
      :handler (fn [ctx [h] [old-h]]
                 [(update old-h :i + (:i h))])}]
    [[[:h :i] 1]]                                           ;; [[[:h] {:i 2}]]
    {::core/db       (assoc default-db :h {:i 2})
     :change-history [[[:h :i] 1]
                      [[:h] {:i 2}]]}))
