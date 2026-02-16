(ns domino.validation-test
  (:require [domino.validation :as validation]
            #?(:clj  [clojure.test :refer :all]
               :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(defn ->hex [s]
  #?(:clj  (format "%02x" (int s))
     :cljs (.toString (.charCodeAt s 0) 16)))

(deftest duplicate-id-keys-present
  (is (= [["duplicate id :fname in the model" {:id :fname}]]
         (:errors (validation/check-valid-model {:model    [[:user {:id :user}
                                                             [:first-name {:id :fname}]
                                                             [:last-name {:id :fname}]]]
                                                 :path-ids #{}}))))
  (is (= [["duplicate id :fname in the model" {:id :fname}]]
         (:errors (validation/check-valid-model {:model    [[:user {:id :fname}
                                                             [:first-name {:id :user}]
                                                             [:last-name {:id :fname}]]]
                                                 :path-ids #{}}))))
  (is (= [["duplicate id :fname in the model" {:id :fname}]]
         (:errors (validation/check-valid-model {:model    [[:user {:id :fname}
                                                             [:first-name {:id :user}]
                                                             [:last-name {:id :lname}
                                                              [:inner {:id :fname}]]]]
                                                 :path-ids #{}})))))

(deftest id-not-in-model
  (is (= [["no path found for :full-name in the model"
           {:id :full-name}]]
         (:errors (validation/validate-schema {:model  [[:user {:id :user}
                                                         [:first-name {:id :fname}]
                                                         [:last-name {:id :lname}]]]
                                               :events [{:inputs  [:fname :lname]
                                                         :outputs [:full-name]
                                                         :handler (fn [_ _ _])}]})))))

(deftest effect-id-not-in-model
  (is (= [["no path found for :nonexistent in the model" {:id :nonexistent}]]
         (:errors (validation/validate-schema {:model   [[:user {:id :user}
                                                           [:first-name {:id :fname}]]]
                                               :events  []
                                               :effects [{:inputs  [:nonexistent]
                                                          :handler (fn [_ _])}]})))))

(deftest maybe-throw-no-errors
  (is (nil? (validation/maybe-throw-exception {:errors []}))))

(deftest maybe-throw-with-errors
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
        #"errors found"
        (validation/maybe-throw-exception {:errors [["some error" {}]]}))))

(deftest event-input-id-not-in-model
  (is (= [["no path found for :missing in the model" {:id :missing}]]
         (:errors (validation/validate-schema
                    {:model  [[:a {:id :a}]]
                     :events [{:inputs [:missing] :outputs [:a] :handler (fn [_ _ _])}]})))))

(deftest valid-ctx
  (is (empty? (:errors (validation/validate-schema {:model   [[:user {:id :user}
                                                               [:first-name {:id :fname}]
                                                               [:last-name {:id :lname}]
                                                               [:full-name {:id :full-name}]]
                                                              [:user-hex {:id :user-hex}]]

                                                    :effects [{:inputs  [:fname :lname :full-name]
                                                               :handler (fn [_ [fname lname full-name]]
                                                                          )}

                                                              {:inputs  [:user-hex]
                                                               :handler (fn [_ [user-hex]]
                                                                          )}]

                                                    :events  [{:inputs  [:fname :lname]
                                                               :outputs [:full-name]
                                                               :handler (fn [_ [fname lname] _]
                                                                          [(or (when (and fname lname) (str lname ", " fname))
                                                                               fname
                                                                               lname)])}

                                                              {:inputs  [:user]
                                                               :outputs [:user-hex]
                                                               :handler (fn [_ [{:keys [first-name last-name full-name]
                                                                                 :or   {first-name "" last-name "" full-name ""}}] _]
                                                                          [(->> (str first-name last-name full-name)
                                                                                (map ->hex)
                                                                                (apply str))])}]})))))
