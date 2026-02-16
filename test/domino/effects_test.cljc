(ns domino.effects-test
  (:require
    [domino.effects :as effects]
    [domino.model :as model]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(deftest effects-test
  (let [data (atom nil)]
    (effects/execute-effects!
      {:domino.core/change-history [[[:a] 1] [[:b] 1]]
       :domino.core/db {:a 1 :b 1}
       :domino.core/model (model/model->paths [[:a {:id :a}]
                                               [:b {:id :b}]])
       :domino.core/effects
                       (effects/effects-by-paths
                         [{:inputs [[:a]] :handler (fn [ctx inputs]
                                                     (reset! data inputs))}])})
    (is (= {:a 1} @data))))

(deftest execute-effect-error-wrapping
  (let [ctx {:domino.core/model (model/model->paths [[:a {:id :a}]])
             :domino.core/db {:a 1}}
        effect {:inputs [[:a]]
                :handler (fn [_ _] (throw (ex-info "boom" {})))}]
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #"failed to execute effect"
          (effects/execute-effect! ctx effect)))
    (try
      (effects/execute-effect! ctx effect)
      (catch #?(:clj Exception :cljs js/Error) e
        (is (= effect (:effect (ex-data e))))
        (is (contains? (ex-data e) :context))))))
