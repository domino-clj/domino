(ns domino.util-test
  (:require [domino.util :refer :all]
            #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(deftest generate-sub-paths-test
  (is (= (generate-sub-paths [:child]) (list [:child])))
  (is (= (generate-sub-paths [:parent :child]) (list [:parent :child]
                                                     [:parent])))
  (is (= (generate-sub-paths [:grand-parent :parent :child]) (list [:grand-parent :parent :child]
                                                                   [:grand-parent :parent]
                                                                   [:grand-parent]))))
