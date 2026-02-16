(ns domino.graph-test
  (:require
    [domino.graph :as graph]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is deftest]])))

(deftest validate-event-empty-outputs
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
        #"invalid events"
        (graph/gen-ev-graph [{:inputs [[:a]] :outputs [] :handler (fn [_ _ _])}]))))

(deftest validate-event-non-fn-handler
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
        #"invalid events"
        (graph/gen-ev-graph [{:inputs [[:a]] :outputs [[:b]] :handler "not-a-fn"}]))))

(deftest valid-graph-no-error
  (let [g (graph/gen-ev-graph [{:inputs [[:a]] :outputs [[:b]] :handler (fn [_ _ _])}])]
    (is (map? g))
    (is (contains? g [:a]))
    (is (contains? g [:b]))))
