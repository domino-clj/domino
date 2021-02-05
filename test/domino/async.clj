(ns domino.async
  (:require
    [domino.core :as core]
    [clojure.test :refer :all]))

;; TODO: fix

(deftest async-handler-test
  (let [result (atom nil)
        ctx (promise)
        ctx' (promise)]
    (core/initialize-async {:model   [[:foo {:id :foo}]
                                      [:bar {:id :bar}]
                                      [:baz {:id :baz}]
                                      [:buz {:id :buz}]]
                            :events  [{:id :ev/bar
                                       :async? true
                                       :inputs  [:foo]
                                       :outputs [:bar]
                                       :handler (fn [{{:keys [foo] :as inputs} :inputs
                                                      :keys [inputs-pre outputs-pre]} on-success on-fail]
                                                  (future
                                                    (Thread/sleep 100)
                                                    (on-success {:bar (inc foo)})))}
                                      {:id :ev/baz
                                       :async? true
                                       :inputs  [:bar]
                                       :outputs [:baz]
                                       :handler (fn [{{:keys [bar] :as inputs} :inputs
                                                      :keys [inputs-pre outputs-pre]} on-success on-fail]
                                                  (future
                                                    (Thread/sleep 100)
                                                    (on-success {:baz (inc bar)})))}
                                      {:id :ev/buz
                                       :inputs  [:baz]
                                       :outputs [:buz]
                                       :handler (fn [{{:keys [baz]} :inputs}]
                                                  {:buz (inc baz)})}]
                            :effects [{:inputs  [:buz]
                                       :handler (fn [{{:keys [buz]} :inputs}]
                                                  (reset! result buz))}]}
                           {}
                     (fn on-success [r]
                       (deliver ctx r))
                     (fn on-fail [e]
                       (throw e)))
    (core/transact-async (deref ctx 10000 nil) [{:foo 1}]
                         (fn [r]
                           (deliver ctx' r))
                         (fn [e]
                           (throw e)))
    (is (= {:foo 1 :bar 2 :baz 3 :buz 4} (:domino.core/db (deref ctx' 10000 nil))))
    (is (= 4 @result))))
