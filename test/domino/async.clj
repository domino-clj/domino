(ns domino.async
  (:require
    [domino.core :as core]
    [clojure.test :refer :all]))

(deftest basic-delay-handler
  (let [ctx (core/initialize
              {:model  [[:foo {:id :foo}]
                        [:bar {:id :bar}]]
               :events [{:inputs  [:foo]
                         :outputs [:bar]
                         :handler (fn [_ {:keys [foo]} _]
                                    (delay {:bar (inc foo)}))}]})]
    (is (= {:foo 1 :bar 2}
           (:domino.core/db (core/transact ctx [[[:foo] 1]]))))))

(deftest cascading-async-handlers
  (let [result (atom nil)
        ctx    (core/initialize
                 {:model   [[:foo {:id :foo}]
                            [:bar {:id :bar}]
                            [:baz {:id :baz}]
                            [:buz {:id :buz}]]
                  :events  [{:inputs  [:foo]
                             :outputs [:bar]
                             :handler (fn [_ {:keys [foo]} _]
                                        (delay {:bar (inc foo)}))}
                            {:inputs  [:bar]
                             :outputs [:baz]
                             :handler (fn [_ {:keys [bar]} _]
                                        (delay {:baz (inc bar)}))}
                            {:inputs  [:baz]
                             :outputs [:buz]
                             :handler (fn [_ {:keys [baz]} _]
                                        {:buz (inc baz)})}]
                  :effects [{:inputs  [:buz]
                             :handler (fn [_ {:keys [buz]}]
                                        (reset! result buz))}]})]
    (:domino.core/db (core/transact ctx [[[:foo] 1]]))
    (is (= 4 @result))))

(deftest mixed-sync-async-handlers
  (let [ctx (core/initialize
              {:model  [[:a {:id :a}]
                        [:b {:id :b}]
                        [:c {:id :c}]]
               :events [{:inputs  [:a]
                         :outputs [:b]
                         :handler (fn [_ {:keys [a]} _]
                                    (delay {:b (inc a)}))}
                        {:inputs  [:b]
                         :outputs [:c]
                         :handler (fn [_ {:keys [b]} _]
                                    {:c (* b 10)})}]})]
    (is (= {:a 1 :b 2 :c 20}
           (:domino.core/db (core/transact ctx [[[:a] 1]]))))))

(deftest nil-via-delay-keeps-old-outputs
  (let [ctx (core/initialize
              {:model  [[:a {:id :a}]
                        [:b {:id :b}]]
               :events [{:inputs  [:a]
                         :outputs [:b]
                         :handler (fn [_ _ _]
                                    (delay nil))}]}
              {:b 42})]
    (is (= {:a 1 :b 42}
           (:domino.core/db (core/transact ctx [[[:a] 1]]))))))

(deftest future-handler
  (let [ctx (core/initialize
              {:model  [[:foo {:id :foo}]
                        [:bar {:id :bar}]]
               :events [{:inputs  [:foo]
                         :outputs [:bar]
                         :handler (fn [_ {:keys [foo]} _]
                                    (future {:bar (* foo 10)}))}]})]
    (is (= {:foo 5 :bar 50}
           (:domino.core/db (core/transact ctx [[[:foo] 5]]))))))

(deftest error-propagation-from-delay
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"failed to execute event"
        (let [ctx (core/initialize
                    {:model  [[:a {:id :a}]
                              [:b {:id :b}]]
                     :events [{:inputs  [:a]
                               :outputs [:b]
                               :handler (fn [_ _ _]
                                          (delay (throw (ex-info "boom" {}))))}]})]
          (core/transact ctx [[[:a] 1]])))))

(deftest backward-compat-async-flag-ignored
  (let [ctx (core/initialize
              {:model  [[:foo {:id :foo}]
                        [:bar {:id :bar}]]
               :events [{:async?  true
                         :inputs  [:foo]
                         :outputs [:bar]
                         :handler (fn [_ {:keys [foo]} _]
                                    (delay {:bar (inc foo)}))}]})]
    (is (= {:foo 1 :bar 2}
           (:domino.core/db (core/transact ctx [[[:foo] 1]]))))))
