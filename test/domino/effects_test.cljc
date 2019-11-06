(ns domino.effects-test
  (:require
    [domino.effects :as effects]
    [domino.model :as model]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(deftest effects-test
  (let [data (atom nil)]
    (effects/execute-effects!
      {:change-history [[[:a] 1] [[:b] 1]]
       :domino.core/db {:a 1 :b 1}
       :domino.core/model (model/model->paths [[:a {:id :a}]
                                               [:b {:id :b}]])
       :domino.core/effects
                       (effects/effects-by-paths
                         [{:inputs [[:a]] :handler (fn [ctx inputs]
                                                     (reset! data inputs))}])})
    (is (= {:a 1} @data))))
