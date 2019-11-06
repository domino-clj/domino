(ns domino.validation-test
  (:require [domino.validation :as validation]
            #?(:clj  [clojure.test :refer :all]
               :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(deftest duplicate-id-keys-present
  (is (try (validation/valid-model? {:model [[:user {:id :user}
                                              [:first-name {:id :fname}]
                                              [:last-name {:id :fname}]]]})
           false
           (catch #?(:clj  Exception
                     :cljs js/Error) _
             true)))
  (is (try (validation/valid-model? {:model [[:user {:id :fname}
                                              [:first-name {:id :user}]
                                              [:last-name {:id :fname}]]]})
           false
           (catch #?(:clj  Exception
                     :cljs js/Error) _
             true)))
  (is (try (validation/valid-model? {:model [[:user {:id :fname}
                                              [:first-name {:id :user}]
                                              [:last-name {:id :lname}
                                               [:inner {:id :fname}]]]]})
           false
           (catch #?(:clj  Exception
                     :cljs js/Error) _
             true))))

(deftest id-not-in-model
  (is (try (validation/valid-events? {:model  [[:user {:id :user}
                                                [:first-name {:id :fname}]
                                                [:last-name {:id :lname}]]]
                                      :events [{:inputs  [:fname :lname]
                                                :outputs [:full-name]
                                                :handler (fn [_ _ _])}]})
           false
           (catch #?(:clj  Exception
                     :cljs js/Error) _
             true))))

(deftest valid-ctx
  (is (some? (validation/validate-context {:model   [[:user {:id :user}
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
                                                                       (map #(format "%02x" (int %)))
                                                                       (apply str))])}]}))))