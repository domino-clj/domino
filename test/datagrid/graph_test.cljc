(ns datagrid.graph-test
  (:require
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])
    [datagrid.graph :as graph]))

(def rules0
  [{:inputs  [:a :b]
    :outputs [:c]}
   {:inputs  [:c]
    :outputs [:d :e]}
   {:inputs  [:g]
    :outputs [:h]}
   {:inputs  [:d]
    :outputs [:f]}])

(def rules1
  [{:inputs  [:a]
    :outputs [:b]}
   {:inputs  [:b]
    :outputs [:a]}
   {:inputs  [:b]
    :outputs [:c]}])

(def rules2
  [{:inputs  [:a]
    :outputs [:b]}
   {:inputs  [:b]
    :outputs [:a]}
   {:inputs  [:b]
    :outputs [:c]}
   {:inputs  [:c]
    :outputs [:d]}

   {:inputs  [:d]
    :outputs [:e]}])

(def rules3
  [{:inputs  [:a]
    :outputs [:a :b]}
   {:inputs  [:b]
    :outputs [:b :a]}])

(def rules4
  [{:inputs  []
    :outputs []}
   {:inputs  [:a]
    :outputs [:b]}])

(deftest add-nodes-test
  (testing "add nodes functionality"
    (let [[entry1 :as entries] (graph/add-nodes {:nodes rules1 :graph {}} [:a])]
      (is (= 2 (count entries)))
      (is (= #{:a} (:visited entry1)))
      (is (= {:a #{:b}} (:graph entry1))))))

(deftest find-related-test
  (testing "find related functionality"
    (is (= [:b]
           (graph/find-related :a rules1)))
    (is (= [:b]
           (graph/find-related :a rules3)))))

(deftest connect-test
  (testing "full connect functionality"
    (is (= {:a #{} :b #{} :c #{:b :a} :d #{:c} :g #{}}
           (graph/connect rules0))))
  (testing "circular dependency"
    (is (= {:a #{:b} :b #{:a}}
           (graph/connect rules1)))
    (is (= {:a #{:b} :b #{:a} :c #{:b} :d #{:c}}
           (graph/connect rules2))))
  (testing "circular eval"
    (is (= {:a #{:b} :b #{:a}}
           (graph/connect rules3))))
  (testing "empty inputs and outputs"
    (is (= {:a #{}}
           (graph/connect rules4))))
  (testing "nil nodes should return empty map"
    (is (= {}                                               ;; TODO: My thoughts are failing silently vs exception here
           (graph/connect nil))))
  (testing "custom input"
    (is (= {:a #{} :b #{} :c #{:a :b}}
           (graph/connect (graph/base-graph-ctx rules0) [:c]))))
  (testing "input not in nodes"
    (is (= {:b #{}}                                         ;; TODO: verify intent. Maybe throw exception
           (graph/connect (graph/base-graph-ctx {}) [:b]))))
  )