(ns domino.util-test
  (:require [domino.util :as util]
            #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(deftest generate-sub-paths-test
  (is (= (util/generate-sub-paths [:child]) (list [:child])))
  (is (= (util/generate-sub-paths [:parent :child]) (list [:parent :child]
                                                     [:parent])))
  (is (= (util/generate-sub-paths [:grand-parent :parent :child]) (list [:grand-parent :parent :child]
                                                                   [:grand-parent :parent]
                                                                   [:grand-parent]))))

(deftest generate-sub-paths-empty
  (is (= [] (util/generate-sub-paths []))))

(deftest map-by-id-test
  (is (= {} (util/map-by-id [])))
  (is (= {} (util/map-by-id [{:name "no id"}])))
  (is (= {:a {:id :a :name "A"}} (util/map-by-id [{:id :a :name "A"}])))
  (is (= {:a {:id :a} :b {:id :b}} (util/map-by-id [{:id :a} {:name "no id"} {:id :b}]))))

(deftest resolve-result-test
  (is (= {:b 1} (util/resolve-result {:b 1})))
  (is (nil? (util/resolve-result nil)))
  (is (= {:b 1} (util/resolve-result (delay {:b 1}))))
  (is (= {:b 1} (util/resolve-result (atom {:b 1})))))
