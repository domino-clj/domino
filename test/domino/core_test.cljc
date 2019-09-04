(ns domino.core-test
  (:require
    [domino.core :refer :all]
    [domino.effects :refer :all]
    [domino.graph :refer :all]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(defn init-ctx [state]
  (atom
    (initialize! {:model   [[:user {:id :user}
                                   [:first-name {:id :fname}]
                                   [:last-name {:id :lname}]
                                   [:full-name {:id :full-name}]]]
                  :effects [{:inputs  [:fname :lname :full-name]
                                   :handler (fn [_ [fname lname full-name]]
                                              (swap! state assoc
                                                     :first-name fname
                                                     :last-name lname
                                                     :full-name full-name))}]
                  :events  [{:inputs  [:fname :lname]
                                   :outputs [:full-name]
                                   :handler (fn [_ [fname lname] _]
                                              [(or (when (and fname lname) (str lname ", " fname))
                                                   fname
                                                   lname)])}]}
                 {})))

(deftest transaction-test
  (let [external-state (atom {})
        ctx            (init-ctx external-state)]
    (swap! ctx transact [[[:user :first-name] "Bob"]])
    (is (= {:first-name "Bob" :last-name nil :full-name "Bob"} @external-state))
    (swap! ctx transact [[[:user :last-name] "Bobberton"]])
    (is (= {:first-name "Bob" :last-name "Bobberton" :full-name "Bobberton, Bob"} @external-state))))

