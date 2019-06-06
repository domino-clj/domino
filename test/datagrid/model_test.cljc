(ns datagrid.model-test
  (:require
    [datagrid.model :refer :all]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])
    [clojure.test :refer :all]))

(deftest model-parse-test
  (let [model [[:title {:validation []}]
               [:user {:id :user}
                [:first-name {:id :fname}]
                [:last-name {:id :lname}]
                [:profile {}
                 [:address {:id :address}
                  [:street {}]
                  [:city {:id :city}]]]]]]
    (is (= {:user    [:user],
            :fname   [:user :first-name],
            :lname   [:user :last-name],
            :address [:user :profile :address],
            :city    [:user :profile :address :city]}
           (model->paths model)))))

