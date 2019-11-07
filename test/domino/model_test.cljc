(ns domino.model-test
  (:require
    [domino.core :as core]
    [domino.graph :as graph]
    [domino.model :refer :all]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

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
           (:id->path (model->paths model))))))

(deftest id-lookup-test
  (let [model [[:title {:validation []}]
               [:user {:id :user}
                [:first-name {:id :fname}]
                [:last-name {:id :lname}]
                [:profile
                 [:address {:id :address}
                  [:street]
                  [:city {:id :city}]]]]]
        ctx   (model->paths model)]

    (is (= :fname (id-for-path ctx [:user :first-name])))
    (is (= :address (id-for-path ctx [:user :profile :address :street])))
    (is (nil? (id-for-path ctx [:profile :address :street])))))

(deftest connect-events-to-model
  (let [model            [[:user {:id :user}
                           [:first-name {:id :fname}]
                           [:last-name {:id :lname}]
                           [:full-name {:id :full-name}]]]
        {:keys [id->path] :as model-paths} (model->paths model)
        events           [{:inputs  [:fname :lname]
                           :outputs [:full-name]
                           :handler (fn [_ [fname lname] _]
                                      [(or (when (and fname lname) (str lname ", " fname)) fname lname)])}]
        connected-events (connect-events model-paths events)]
    (is (= {:inputs  [[:user :first-name] [:user :last-name]]
            :outputs [[:user :full-name]]}
           (dissoc (first connected-events) :handler)))
    (is (=
          {::core/db {:user {:first-name "Bob"}}
           :change-history  [[[:user :first-name] "Bob"]]}
          (select-keys
            (graph/execute-events {::core/db    {}
                                   ::core/graph (graph/gen-ev-graph events)}
                                  [[(id->path :fname) "Bob"]])
            [::core/db :change-history])))
    (is (=
          {::core/db {:user {:first-name "Bob"
                             :last-name  "Bobberton"}}
           :change-history  [[[:user :first-name] "Bob"] [[:user :last-name] "Bobberton"]]}
          (select-keys
            (graph/execute-events {::core/db    {}
                                   ::core/graph (graph/gen-ev-graph events)}
                                  [[(id->path :fname) "Bob"]
                                   [(id->path :lname) "Bobberton"]])
            [::core/db :change-history])))))

