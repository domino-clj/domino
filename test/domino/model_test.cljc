(ns domino.model-test
  (:require
    [domino.core :as core]
    [domino.graph :as graph]
    [domino.events :as events]
    [domino.model :as model]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

;; TODO: move model logic to model ns.
(comment
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
             (:id->path (model/model->paths model))))))

  (deftest id-lookup-test
    (let [model [[:title {:validation []}]
                 [:user {:id :user}
                  [:first-name {:id :fname}]
                  [:last-name {:id :lname}]
                  [:profile
                   [:address {:id :address}
                    [:street]
                    [:city {:id :city}]]]]]
          ctx   (model/model->paths model)]

      (is (= :fname (model/id-for-path ctx [:user :first-name])))
      (is (= :address (model/id-for-path ctx [:user :profile :address :street])))
      (is (nil? (model/id-for-path ctx [:profile :address :street])))))

  (deftest connect-events-to-model
    (let [model            [[:user {:id :user}
                             [:first-name {:id :fname}]
                             [:last-name {:id :lname}]
                             [:full-name {:id :full-name}]]]
          {:keys [id->path] :as model-paths} (model/model->paths model)
          events           [{:inputs  [:fname :lname]
                             :outputs [:full-name]
                             :handler (fn [_ [fname lname] _]
                                        [(or (when (and fname lname) (str lname ", " fname)) fname lname)])}]
          connected-events (model/connect-events model-paths events)]
      (is (= {:inputs  [[:user :first-name] [:user :last-name]]
              :outputs [[:user :full-name]]}
             (dissoc (first connected-events) :handler)))
      (is (=
           {::core/db {:user {:first-name "Bob"}}
            ::core/change-history  [[[:user :first-name] "Bob"]]}
           (select-keys
            (events/execute-events {::core/db    {}
                                    ::core/graph (graph/gen-ev-graph events)}
                                   [[(id->path :fname) "Bob"]])
            [::core/db ::core/change-history])))
      (is (=
           {::core/db {:user {:first-name "Bob"
                              :last-name  "Bobberton"}}
            ::core/change-history  [[[:user :first-name] "Bob"] [[:user :last-name] "Bobberton"]]}
           (select-keys
            (events/execute-events {::core/db    {}
                                    ::core/graph (graph/gen-ev-graph events)}
                                   [[(id->path :fname) "Bob"]
                                    [(id->path :lname) "Bobberton"]])
            [::core/db ::core/change-history]))))))
