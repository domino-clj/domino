(ns datagrid.test-core
  (:require
    [datagrid.graph :refer :all]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(deftest empty-events
  (testing "running without events should return only the original changes"))

(deftest event-with-errors
  (testing "running a event with errors should throw those errors"))

(deftest no-matching-events
  (testing "running without any matching events should return only the original changes"))

(deftest event-returning-a-nil
  (testing "events returning a nil should return the original value of the event's output"))

(deftest independent-events
  (testing "all related events are run & unrelated events are not run"))

(deftest events-with-context
  (testing "events should be able to access and modify context"))

(deftest event-not-triggered
  (testing "event is not triggered because input hasn't changed"))

(deftest event-triggered-by-output-path
  (testing "events chain from output paths"))

(deftest event-cycles
  (testing "testing that event cycles evaluate exactly once"))

(deftest test-event-returning-a-nil
  (testing "returning [nil] from a event"))

(deftest test-find-related-paths
  (testing "find-related-paths correctly finds connected subgraph"))

(deftest test-group-paths-by-related-subgraph
  (testing "group-paths-by-related-subgraph"))

(deftest events-test
  (testing "chained events are run in order"))
