(ns domino.async
  (:require
    [domino.core :as core]
    [clojure.test :refer :all]))

(deftest async-handler-test
  (let [result (atom nil)
        ctx    (core/initialize {:model   [[:foo {:id :foo}]
                                           [:bar {:id :bar}]
                                           [:baz {:id :baz}]
                                           [:buz {:id :buz}]]
                                 :events  [{:async? true
                                            :inputs  [:foo]
                                            :outputs [:bar]
                                            :handler (fn [ctx {:keys [foo]} _ cb]
                                                       (cb {:bar (inc foo)}))}
                                           {:async? true
                                            :inputs  [:bar]
                                            :outputs [:baz]
                                            :handler (fn [ctx {:keys [bar]} _ cb]
                                                       (cb {:baz (inc bar)}))}
                                           {:inputs  [:baz]
                                            :outputs [:buz]
                                            :handler (fn [ctx {:keys [baz]} _]
                                                       {:buz (inc baz)})}]
                                 :effects [{:inputs  [:buz]
                                            :handler (fn [ctx {:keys [buz]}]
                                                       (reset! result buz))}]})]
    (:domino.core/db (core/transact ctx [[[:foo] 1]]))
    (is (= 4 @result))))
