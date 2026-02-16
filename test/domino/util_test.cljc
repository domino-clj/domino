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

(deftest dissoc-in-test
  (is (= {:a {:b {:d 4}}}
         (util/dissoc-in {:a {:b {:c 3 :d 4}}} [:a :b :c])))
  (is (= {:a {:other 1}}
         (util/dissoc-in {:a {:b {:c 3} :other 1}} [:a :b :c]))
      "removes empty parent maps")
  (is (= {}
         (util/dissoc-in {:a {:b {:c 3}}} [:a :b :c]))
      "removes all empty ancestors")
  (is (= {:b 2}
         (util/dissoc-in {:a 1 :b 2} [:a]))
      "works with single key")
  (is (= {:a 1}
         (util/dissoc-in {:a 1} [:b]))
      "no-op when key missing"))

(deftest random-uuid-test
  (let [id (util/random-uuid)]
    (is (some? id))
    (is (not= id (util/random-uuid)))))
