(ns domino.async
  (:require
    [domino.core :as core]
    [clojure.test :refer :all]))

;; TODO: fix

(def result (atom nil))

(def run-count (atom {:ev/bar 0
                      :ev/baz 0
                      :ev/buz 0}))

(defn reset-run-count! []
  (reset! run-count {:ev/bar 0
                     :ev/baz 0
                     :ev/buz 0}))

(def sync-schema {:model   [[:index-id {:id :idx}]
                            [:foo {:id :foo}]
                            [:bar {:id :bar}]
                            [:baz {:id :baz}]
                            [:buz {:id :buz}]]
                  :events  [{:id :ev/bar
                             :inputs  [:foo]
                             :outputs [:bar]
                             :handler (fn [{{:keys [foo] :as inputs} :inputs
                                            :keys [inputs-pre outputs-pre]}]
                                        {:bar (inc foo)})}
                            {:id :ev/baz
                             :inputs  [:bar]
                             :outputs [:baz]
                             :handler (fn [{{:keys [bar] :as inputs} :inputs
                                            :keys [inputs-pre outputs-pre]}]
                                        {:baz (inc bar)})}
                            {:id :ev/buz
                             :inputs  [:baz]
                             :outputs [:buz]
                             :handler (fn [{{:keys [baz]} :inputs}]
                                        {:buz (inc baz)})}]})

(def async-schema {:model   [[:index-id {:id :idx}]
                             [:foo {:id :foo}]
                             [:bar {:id :bar}]
                             [:baz {:id :baz}]
                             [:buz {:id :buz}]]
                   :events  [{:id :ev/bar
                              :async? true
                              :inputs  [:foo]
                              :outputs [:bar]
                              #_#_  :should-run (fn ev-bar-should-run [{:keys [inputs inputs-pre]}]
                                            (not= inputs inputs-pre))
                              :handler (fn ev-bar-h [{{:keys [foo] :as inputs} :inputs
                                                      :keys [inputs-pre outputs-pre]} on-success on-fail]
                                         (swap! run-count update :ev/bar inc)
                                         (future
                                           (Thread/sleep 100)
                                           (on-success {:bar (inc foo)})))}
                             {:id :ev/baz
                              :async? true
                              :inputs  [:bar]
                              :outputs [:baz]
                              #_#_   :should-run (fn ev-baz-should-run [{:keys [inputs inputs-pre]}]
                                            (not= inputs inputs-pre))
                              :handler (fn [{{:keys [bar] :as inputs} :inputs
                                             :keys [inputs-pre outputs-pre]} on-success on-fail]
                                         (swap! run-count update :ev/baz inc)
                                         (future
                                           (Thread/sleep 100)
                                           (on-success {:baz (inc bar)})))}
                             {:id :ev/buz
                              :inputs  [:baz]
                              :outputs [:buz]
                            #_#_  :should-run (fn [{:keys [inputs inputs-pre]}]
                                            (not= inputs inputs-pre))
                              :handler (fn [{{:keys [baz]} :inputs}]
                                         {:buz (inc baz)})}]
                   :effects [{:inputs  [:buz]
                              :handler (fn [{{:keys [buz]} :inputs}]
                                         (swap! run-count update :ev/buz inc)
                                         (reset! result buz))}]})

(def async-subcontext-singleton-schema {:model [[:a {:id :a}]
                                                [:async-sub {:id :subctx
                                                             :schema async-schema}]
                                                [:sync-sub {:id :sync-sub
                                                            :schema sync-schema}]]})

(def async-subcontext-coll-schema {:model [[:a {:id :a}]
                                           [:async-subs {:id :subctxs
                                                         :collection? true
                                                         :index-id :idx
                                                         :schema async-schema}]
                                           [:sync-subs {:id :sync-subs
                                                        :collection? true
                                                        :index-id :idx
                                                        :schema sync-schema}]]})

(deftest async-handler-test
  (reset-run-count!)
  (reset! result nil)
  (let [ctx (promise)
        ctx' (promise)]
    (core/initialize-async async-schema
                           {}
                     (fn on-success [r]
                       (deliver ctx r))
                     (fn on-fail [e]
                       (throw e)))
    (let [ctx-v (deref ctx 10000 nil)]
      (is (= {:ev/bar 0
              :ev/baz 0
              :ev/buz 0} @run-count))
      (reset-run-count!)
      (core/transact-async ctx-v [{:foo 1}]
                           (fn [r]
                             (deliver ctx' r))
                           (fn [e]
                             (throw e))))
    (let [ctx'-v (deref ctx' 10000 nil)]
      (is (= {:ev/bar 1
              :ev/baz 1
              :ev/buz 1} @run-count))
      (reset-run-count!)
      (is (= {:foo 1 :bar 2 :baz 3 :buz 4} (:domino.core/db ctx'-v)))
      (is (= 4 @result)))))

(deftest async-subcontext-test
  (reset-run-count!)
  (reset! result nil)
  (let [ctx (promise)
        ctx' (promise)]
    (core/initialize-async async-subcontext-singleton-schema
                           {:async-sub {:index-id 1}}
                           (fn on-success [r]
                             (deliver ctx r))
                           (fn on-fail [e]
                             (throw e)))
    (let [ctx-v (deref ctx 10000 nil)]
      (is (= {:ev/bar 0
              :ev/baz 0
              :ev/buz 0} @run-count))
      (reset-run-count!)
      (core/transact-async ctx-v [[:domino.core/set-value :a 1]
                                  [:domino.core/update-child [:subctx]
                                   [:domino.core/set-value :foo 1]]
                                  [:domino.core/update-child [:sync-sub]
                                   [:domino.core/set-value :foo 4]]]
                           (fn [r]
                             (deliver ctx' r))
                           (fn [e]
                             (throw e))))
    (let [ctx'-v (deref ctx' 10000 nil)]
      (is (= {:ev/bar 1
              :ev/baz 1
              :ev/buz 1} @run-count))
      (reset-run-count!)
      (is (= {:a 1
              :async-sub {:index-id 1 :foo 1 :bar 2 :baz 3 :buz 4}
              :sync-sub  {:foo 4 :bar 5 :baz 6 :buz 7}} (:domino.core/db ctx'-v))))
    (is (= 4 @result))))

(deftest async-subcontext-coll-test
  (reset-run-count!)
  (reset! result nil)
  (let [ctx (promise)
        ctx' (promise)]
    (core/initialize-async async-subcontext-coll-schema
                           {:async-subs {1 {:index-id 1}}}
                           (fn on-success [r]
                             (deliver ctx r))
                           (fn on-fail [e]
                             (throw e)))
    (let [ctx-v (deref ctx 10000 nil)]
      (is (= {:ev/bar 0
              :ev/baz 0
              :ev/buz 0} @run-count))
      (reset-run-count!)
      (core/transact-async ctx-v
                           [[:domino.core/set-value :a 1]
                            [:domino.core/update-child [:subctxs 1]
                             [:domino.core/set-value :foo 1]]
                            [:domino.core/update-child [:subctxs 0]
                             [:domino.core/set-value :foo 1]]
                            [:domino.core/update-child [:sync-subs 1]
                             [:domino.core/set-value :foo 4]]
                            [:domino.core/update-child [:sync-subs 0]
                             [:domino.core/set-value :foo 5]]]
                           (fn [r]
                             (deliver ctx' r))
                           (fn [e]
                             (throw e))))
    (let [ctx'-v (deref ctx' 10000 nil)]
      (is (= {:ev/bar 2
              :ev/baz 2
              :ev/buz 2} @run-count))
      (reset-run-count!)
      (is (= {:a 1
              :async-subs {0 {:index-id 0 :foo 1 :bar 2 :baz 3 :buz 4}
                           1 {:index-id 1 :foo 1 :bar 2 :baz 3 :buz 4}}
              :sync-subs {0 {:index-id 0 :foo 5 :bar 6 :baz 7 :buz 8}
                          1 {:index-id 1 :foo 4 :bar 5 :baz 6 :buz 7}}} (:domino.core/db ctx'-v))))
    (is (= 4 @result))))
