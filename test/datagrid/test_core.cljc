(ns datagrid.test-core
  (:require
    [datagrid.core :refer :all]
    [datagrid.effects :refer :all]
    [datagrid.graph :refer :all]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(defn init-ctx [state]
  (atom
    (initialize! {:model/model   [[:user {:id :user}
                                   [:first-name {:id :fname}]
                                   [:last-name {:id :lname}]
                                   [:full-name {:id :full-name}]]]
                  :model/effects [{:inputs  [:fname :lname :full-name]
                                   :handler (fn [_ [fname lname full-name]]
                                              (println ">>>>" fname lname full-name)
                                              (swap! state assoc
                                                     :first-name fname
                                                     :last-name lname
                                                     :full-name full-name))}]
                  :model/events  [{:inputs  [:fname :lname]
                                   :outputs [:full-name]
                                   :handler (fn [_ [fname lname] _]
                                              [(or (when (and fname lname) (str lname ", " fname))
                                                   fname
                                                   lname)])}]}
                 {})))

(deftest transaction-test
  (let [external-state (atom {})
        ctx            (init-ctx external-state)]
    (println (:datagrid.core/db @ctx))
    (swap! ctx transact [[[:user :first-name] "Bob"]])
    (println (:datagrid.core/db @ctx))
    #_(is (= {:first-name "Bob", :last-name nil, :full-name nil} @external-state))
    (swap! ctx transact [[[:user :last-name] "Bobberton"]])
    (println (:datagrid.core/db @ctx))
    (println @external-state)
    #_(is (= {:first-name "Bob", :last-name nil, :full-name nil} @external-state))))

