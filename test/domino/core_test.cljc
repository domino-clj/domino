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
                                   [:full-name {:id :full-name}]]
                            [:user-hex {:id :user-hex}]]

                  :effects [{:inputs  [:fname :lname :full-name]
                             :handler (fn [_ [fname lname full-name]]
                                        (swap! state assoc
                                               :first-name fname
                                               :last-name lname
                                               :full-name full-name))}

                            {:inputs [:user-hex]
                             :handler (fn [_ [user-hex]]
                                        (swap! state assoc
                                               :user-hex user-hex))}]

                  :events  [{:inputs  [:fname :lname]
                             :outputs [:full-name]
                             :handler (fn [_ [fname lname] _]
                                        [(or (when (and fname lname) (str lname ", " fname))
                                             fname
                                             lname)])}

                            {:inputs [:user]
                             :outputs [:user-hex]
                             :handler (fn [_ [{:keys [first-name last-name full-name]
                                              :or {first-name "" last-name "" full-name ""}}] _]
                                        [(->> (str first-name last-name full-name)
                                              (map #(format "%02x" (int %)))
                                              (apply str))])}]}
                 {})))

(deftest transaction-test
  (let [external-state (atom {})
        ctx            (init-ctx external-state)]
    (swap! ctx transact [[[:user :first-name] "Bob"]])
    (is (= {:first-name "Bob" :last-name nil :full-name "Bob"
            :user-hex "426f62426f62"} @external-state))
    (swap! ctx transact [[[:user :last-name] "Bobberton"]])
    (is (= {:first-name "Bob" :last-name "Bobberton" :full-name "Bobberton, Bob"
            :user-hex "426f62426f62626572746f6e426f62626572746f6e2c20426f62"} @external-state))))

