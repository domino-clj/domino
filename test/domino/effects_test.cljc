(ns domino.effects-test
  (:require
   [domino.core :as core]
   #?(:clj  [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

;; TODO: Move effect logic to effects ns.
;;       Test only effect logic

(deftest effects-test
  (let [data (atom nil)]
    (is (= {::core/transaction-report {:status :complete
                                       :changes [{:change
                                                  [::core/set-value :a 1]
                                                  :id :a
                                                  :status :complete}]
                                       :triggered-effects
                                       '(:my-effect)}}
           (-> {:model [[:a {:id :a}]]
                :effects [{:id :my-effect
                           :inputs [:a]
                           :handler (fn [{:keys [inputs]}]
                                      (reset! data inputs))}]}
               core/initialize
               (core/transact [{:a 1}])
               (select-keys [::core/transaction-report]))))
    (is (= {:a 1} @data))))
