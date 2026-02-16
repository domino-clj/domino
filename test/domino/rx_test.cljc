(ns domino.rx-test
  (:require
    [domino.rx :as rx]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(deftest create-reactive-map-test
  (let [m (rx/create-reactive-map {:x 1 :y 2})]
    (is (= {:x 1 :y 2} (rx/get-reaction m ::rx/root)))))

(deftest add-reaction-and-compute-test
  (let [m (-> (rx/create-reactive-map {:x 1 :y 2})
              (rx/add-reaction! :sum (fn [root] (+ (:x root) (:y root)))))]
    (is (= 3 (rx/get-reaction m :sum)))))

(deftest chained-reactions-test
  (let [m (-> (rx/create-reactive-map {:x 2})
              (rx/add-reaction! :doubled (fn [root] (* 2 (:x root))))
              (rx/add-reaction! :quad :doubled (fn [d] (* 2 d))))]
    (is (= 4 (rx/get-reaction m :doubled)))
    (is (= 8 (rx/get-reaction m :quad)))))

(deftest set-value-and-clear-watchers-test
  (let [m (-> (rx/create-reactive-map {:x 1})
              (rx/add-reaction! :inc-x (fn [root] (inc (:x root)))))]
    (is (= 2 (rx/get-reaction m :inc-x)))
    (let [m' (rx/set-value m {:x 10})]
      (is (= {:x 10} (rx/get-reaction m' ::rx/root)))
      (is (= 11 (rx/get-reaction m' :inc-x))))))

(deftest add-reactions-with-dependency-ordering-test
  (let [m (-> (rx/create-reactive-map {:n 5})
              (rx/add-reactions!
                [{:id   :doubled
                  :args ::rx/root
                  :fn   (fn [root] (* 2 (:n root)))}
                 {:id   :quadrupled
                  :args :doubled
                  :fn   (fn [d] (* 2 d))}]))]
    (is (= 10 (rx/get-reaction m :doubled)))
    (is (= 20 (rx/get-reaction m :quadrupled)))))

(deftest unregistered-reaction-error-test
  (let [m (rx/create-reactive-map {:x 1})]
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #"not registered"
          (rx/get-reaction m :nonexistent)))))

(deftest missing-input-error-test
  (let [m (rx/create-reactive-map {:x 1})]
    (is (thrown?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          (rx/add-reaction! m :bad :missing-input (fn [x] x))))))
