(ns domino.events-test
  (:require
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])
    [domino.graph :as graph]
    [domino.events :as events]
    [domino.model :as model]
    [domino.core :as core]))

(def test-model
  (model/model->paths
    [[:a {:id :a}]
     [:b {:id :b}]
     [:c {:id :c}]
     [:d {:id :d}]
     [:e {:id :e}]
     [:f {:id :f}]
     [:g {:id :g}]
     [:h {:id :h}
      [:i {:id :i}]]]))

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
              {::core/model test-model
               ::core/db    db
               ::core/graph (graph/gen-ev-graph events)})
            (events/execute-events inputs)
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
      :handler (fn [ctx {:keys [a]} {:keys [b]}] {:b (+ a b)})}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1 :b 1)
     :change-history [[[:a] 1] [[:b] 1]]}))

(deftest unmatched-event
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx _ _] {:b 5})}]
    [[[:c] 1]]
    {::core/db       (assoc default-db :c 1)
     :change-history [[[:c] 1]]}))

(deftest nil-value
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [_ _ _] {:b nil})}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1 :b nil)
     :change-history [[[:a] 1] [[:b] nil]]}))

(deftest exception-bubbles-up
  (is
    (thrown?
      #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
      (events/execute-events
        {::core/db    default-db
         ::core/graph (graph/gen-ev-graph
                        [{:inputs  [[:a]]
                          :outputs [[:b]]
                          :handler (fn [ctx {:keys [a]} {:keys [b]}]
                                     (throw (ex-info "test" {:test :error})))}])}
        [[[:a] 1]]))))

(deftest single-unchanged-input
  ;; todo might be better to not run any events if the inputs are the same as the current model
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx {:keys [a]} {:keys [b]}] {:b (inc a)})}]
    [[[:a] 0]]
    {::core/db       (assoc default-db :b 1)
     :change-history [[[:a] 0] [[:b] 1]]}))

(deftest same-input-as-output
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:a]]
      :handler (fn [ctx {:keys [a]} _] {:a (inc a)})}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 2)
     :change-history [[[:a] 1]
                      [[:a] 2]]}))

(deftest cyclic-inputs
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx {:keys [a]} {:keys [b c]}] {:b (inc b) :c c})}
     {:inputs  [[:b]]
      :outputs [[:a]]
      :handler (fn [ctx {:keys [b]} {:keys [a]}] {:a (inc a)})}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :b 1 :a 2)
     :change-history [[[:a] 1]
                      [[:b] 1]
                      [[:a] 2]]})
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx {:keys [a]} {:keys [b c]}] {:b (+ a b) :c c})}
     {:inputs  [[:b]]
      :outputs [[:a]]
      :handler (fn [ctx {:keys [b]} {:keys [a]}] {:a (+ b a)})}]
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
      :handler (fn [ctx {:keys [a]} {:keys [b c]}] {:b (+ a b) :c (+ a c)})}
     {:inputs  [[:c]]
      :outputs [[:d]]
      :handler (fn [ctx {:keys [c]} _] {:d (inc c)})}]
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
      :handler (fn [ctx {:keys [a b]} {:keys [c]}] {:c (+ a b)})}]
    [[[:a] 1] [[:b] 1]]
    {::core/db       (assoc default-db :a 1 :b 1 :c 2)
     :change-history [[[:a] 1] [[:b] 1] [[:c] 2]]}))

(deftest multi-output-event
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx {:keys [a]} {:keys [b c]}] {:b (+ a b) :c (inc c)})}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1 :b 1 :c 1)
     :change-history [[[:a] 1] [[:b] 1] [[:c] 1]]}))

(deftest multi-input-output-event-omitted-unchanged-results
  (test-graph-events
    [{:inputs  [[:a] [:b]]
      :outputs [[:b] [:c] [:d]]
      :handler (fn [ctx {:keys [a b]} _] {:b (+ a b)})}]
    [[[:a] 1] [[:b] 1]]
    {::core/db       (assoc default-db :a 1 :b 2)
     :change-history [[[:a] 1]
                      [[:b] 1]
                      [[:b] 2]]}))

(deftest multi-input-output-event
  (test-graph-events
    [{:inputs  [[:a] [:b]]
      :outputs [[:b] [:c] [:d]]
      :handler (fn [ctx {:keys [a b]} {:keys [c d]}] {:b (+ a b) :c c :d d})}]
    [[[:a] 1] [[:b] 1]]
    {::core/db       (assoc default-db :a 1 :b 2)
     :change-history [[[:a] 1]
                      [[:b] 1]
                      [[:b] 2]]}))

(deftest unrelated-events
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx {:keys [a]} _] {:b (inc a)})}
     {:inputs  [[:c]]
      :outputs [[:d]]
      :handler (fn [ctx {:keys [c]} _] {:d (dec c)})}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1 :b 2)
     :change-history [[[:a] 1] [[:b] 2]]}))

(deftest context-access
  (test-graph-events
    {:action #(+ % 5)}
    default-db
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx {:keys [a]} _] {:b ((:action ctx) a)})}]
    [[[:a] 1]]
    {::core/db       (assoc default-db :a 1 :b 6)
     :change-history [[[:a] 1] [[:b] 6]]}))

(deftest triggering-sub-path
  (test-graph-events
    [{:inputs  [[:h]]
      :outputs [[:h]]
      :handler (fn [ctx {h :h} {old-h :h}]
                 {:h (update old-h :i + (:i h))})}]
    [[[:h :i] 1]]                                           ;; [[[:h] {:i 2}]]
    {::core/db       (assoc default-db :h {:i 2})
     :change-history [[[:h :i] 1]
                      [[:h] {:i 2}]]}))
