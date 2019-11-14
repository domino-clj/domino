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
