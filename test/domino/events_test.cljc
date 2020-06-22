(ns domino.events-test
  (:require
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])
    [domino.graph :as graph]
    [domino.events :as events]
    [domino.model :as model]
    [domino.core :as core]))


;; TODO: move event logic to events ns
;; TODO: evaluate migration of change-history behaviour
;; TODO: Consider adding args map to change-history/event-history

(def test-schema
  {:model
   [[:a {:id :a}]
    [:b {:id :b}]
    [:c {:id :c}]
    [:d {:id :d}]
    [:e {:id :e}]
    [:f {:id :f}]
    [:g {:id :g}]
    [:h {:id :h}
     [:i {:id :i}]]]})

(def default-db {:a 0, :b 0, :c 0, :d 0, :e 0, :f 0, :g 0 :h {:i 0}})

(defn test-events-on-db [db events changes result]
  (is
   (= result
      (->
       test-schema
       (assoc :events events)
       (core/initialize db)
       (core/transact changes)
       (select-keys [::core/db
                     ::core/transaction-report])))))

(def test-events (partial test-events-on-db default-db))


(deftest no-events
  (test-events
   []
   [{:a 1}]
   {::core/db (assoc default-db :a 1)
    ::core/transaction-report {:status :complete
                               :changes [[::core/set-value :a 1]]}}))

(deftest nil-output-ignored
  (test-events
   [{:id :my-event
     :inputs [:a]
     :outputs [:b]
     :handler (fn [_])}]
   [{:a 1}]
   {::core/db (assoc default-db :a 1)
    ::core/transaction-report {:status :complete
                               :changes [[::core/set-value :a 1]]}}))

(deftest single-input-output
  (test-events
   [{:id :my-event
     :inputs [:a]
     :outputs [:b]
     :handler (fn [{{:keys [a]} :inputs
                    {:keys [b]} :outputs}]
                {:b (+ a b)})}]
   [{:a 1}]
   {::core/db (assoc default-db :a 1 :b 1)
    ::core/transaction-report {:status :complete
                               :changes [[::core/set-value :a 1]
                                         [::core/set-value :b 1]]
                               :event-history [:my-event]}}))

(deftest unmatched-event
  ;; don't set :a in db, since initialize is being used
  (test-events-on-db
   (dissoc default-db :a)
   [{:id :my-event
     :inputs [:a]
     :outputs [:b]
     :handler (fn [{{:keys [a]} :inputs
                    {:keys [b]} :outputs}]
                {:b (inc b)})}]
   [{:c 1}]
   {::core/db (-> default-db
                  (dissoc :a)
                  (assoc :c 1))
    ::core/transaction-report {:status :complete
                               :changes [[::core/set-value :c 1]]}}))

(deftest nil-value
  ;; NOTE: Explicit nils are NO LONGER SUPPORTED
  (test-events-on-db
   (dissoc default-db :a)
   [{:id :my-event
     :inputs  [:a]
     :outputs [:b]
     :handler (fn [_] {:b nil})}]
   [[:a 1]]
   {::core/db             (-> default-db
                              (assoc :a 1)
                              (dissoc :b))
    ::core/transaction-report {:status :complete
                               :changes [[::core/set-value :a 1]
                                         [::core/remove-value :b]]
                               :event-history [:my-event]}}))


(deftest output-dependent-event
  ;; NOTE: This behaviour has changed such that the DB is updated between events.
  (test-events
   [{:id :first
     :inputs [:a :b]
     :outputs [:c]
     :handler (fn [{{:keys [a b]} :inputs}]
                {:c (+ a b)})}
    {:id :second
     :inputs [:a :c]
     :outputs [:d]
     :handler (fn [{{:keys [a c]} :inputs}]
                {:d (+ a c)})}]
   [{:a 1}]
   {::core/db (assoc default-db :a 1 :c 1 :d 2)
    ::core/transaction-report {:status :complete
                               :changes [[::core/set-value :a 1]
                                         [::core/set-value :c 1]
                                         [::core/set-value :d 2]]
                               :event-history [:first :second]}}))

(deftest exception-bubbles-up
  ;; NOTE: Due to async changes, errors can't be thrown. They must be returned.
  ;; TODO: add specific reason with event id to tx-report
  (test-events-on-db
   (dissoc default-db :a)
   [{:inputs [:a]
     :outputs [:b]
     :handler (fn [{inputs :inputs}]
                (throw (ex-info "Foo" {:test :error
                                       :inputs inputs})))}]
   [{:a 1}]
   {::core/db (dissoc default-db :a)
    ::core/transaction-report {:status :failed
                               :reason ::core/unknown-error
                               :message "Foo"
                               :data {:test :error
                                      :inputs {:a 1}}}}))


(deftest single-unchanged-input
  (test-events
   [{:inputs  [:a]
     :outputs [:b]
     :handler (fn [{{:keys [a]} :inputs}] {:b (inc a)})}]
   [[:a 0]]
   {::core/db             (assoc default-db :b 1) ;; NOTE: this is due to `core/initialize`!
    ::core/transaction-report {:status :complete
                               :changes [[::core/set-value :a 0]]}}))

(deftest same-input-as-output
  ;; TODO: Fix Event running logic
  (test-events
   [{:id :my-event
     :inputs  [:a]
     :outputs [:a]
     :handler (fn [{{:keys [a]} :inputs}] {:a (inc a)})}]
   [[:a 1]]
    {::core/db             (assoc default-db :a 2)
     ::core/transaction-report {:status :complete
                                :changes [[::core/set-value :a 1]
                                          [::core/set-value :a 2]]
                                :event-history [:my-event]}}))


(deftest cyclic-inputs
  ;; TODO: Fix event running logic
  (test-events
   [{:id :first
     :inputs  [:a]
     :outputs [:b :c]
     :handler (fn [{{:keys [a]} :inputs
                    {:keys [b c]} :outputs}]
                {:b (inc b) :c c})}
    {:id :second
     :inputs  [:b]
     :outputs [:a]
     :handler (fn [{{:keys [b]} :inputs
                    {:keys [a]} :outputs}]
                {:a (inc a)})}]
   [[:a 1]]
    {::core/db             (assoc default-db :b 1 :a 2)
     ::core/transaction-report {:status :complete
                                :changes [[::core/set-value :a 1]
                                          [::core/set-value :b 1]
                                          [::core/set-value :a 2]]
                                :event-history [:first :second]}})
  (test-events
   [{:id :first
     :inputs  [:a]
     :outputs [:b :c]
     :handler (fn [{{:keys [a]} :inputs
                    {:keys [b c]} :outputs}]
                {:b (+ a b) :c c})}
    {:id :second
     :inputs  [:b]
     :outputs [:a]
     :handler (fn [{{:keys [b]} :inputs
                    {:keys [a]} :outputs}]
                {:a (+ b a)})}]
   [[:a 1] [:b 2]]
    {::core/db             (assoc default-db :b 3 :a 4)
     ::core/transaction-report {:status :complete
                                :changes [[::core/set-value :a 1]
                                          [::core/set-value :b 2]
                                          [::core/set-value :b 3]
                                          [::core/set-value :a 4]]
                                :event-history [:first :second]}}))

;; TODO: rest of NS

#_
(deftest test-cascading-events
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx {:keys [a]} {:keys [b c]}] {:b (+ a b) :c (+ a c)})}
     {:inputs  [[:c]]
      :outputs [[:d]]
      :handler (fn [ctx {:keys [c]} _] {:d (inc c)})}]
    [[[:a] 1] [[:b] 1]]
    {::core/db             (assoc default-db :a 1 :b 2 :c 1 :d 2)
     ::core/change-history [[[:a] 1]
                            [[:b] 1]
                            [[:b] 2]
                            [[:c] 1]
                            [[:d] 2]]}))

#_
(deftest multi-input-event
  (test-graph-events
    [{:inputs  [[:a] [:b]]
      :outputs [[:c]]
      :handler (fn [ctx {:keys [a b]} {:keys [c]}] {:c (+ a b)})}]
    [[[:a] 1] [[:b] 1]]
    {::core/db             (assoc default-db :a 1 :b 1 :c 2)
     ::core/change-history [[[:a] 1] [[:b] 1] [[:c] 2]]}))

#_
(deftest multi-output-event
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx {:keys [a]} {:keys [b c]}] {:b (+ a b) :c (inc c)})}]
    [[[:a] 1]]
    {::core/db             (assoc default-db :a 1 :b 1 :c 1)
     ::core/change-history [[[:a] 1] [[:b] 1] [[:c] 1]]}))

#_
(deftest multi-input-output-event-omitted-unchanged-results
  (test-graph-events
    [{:inputs  [[:a] [:b]]
      :outputs [[:c] [:d] [:e]]
      :handler (fn [ctx {:keys [a b]} _] {:c (+ a b)})}]
    [[[:a] 1] [[:b] 1]]
    {::core/db             (assoc default-db :a 1 :b 1 :c 2)
     ::core/change-history [[[:a] 1]
                            [[:b] 1]
                            [[:c] 2]]}))

#_
(deftest multi-input-output-event
  (test-graph-events
    [{:inputs  [[:a] [:b]]
      :outputs [[:c] [:d] [:e]]
      :handler (fn [ctx {:keys [a b]} {:keys [d e]}] {:c (+ a b) :d d :e e})}]
    [[[:a] 1] [[:b] 1]]
    {::core/db             (assoc default-db :a 1 :c 2 :b 1)
     ::core/change-history [[[:a] 1]
                            [[:b] 1]
                            [[:c] 2]]}))

#_
(deftest unrelated-events
  (test-graph-events
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx {:keys [a]} _] {:b (inc a)})}
     {:inputs  [[:c]]
      :outputs [[:d]]
      :handler (fn [ctx {:keys [c]} _] {:d (dec c)})}]
    [[[:a] 1]]
    {::core/db             (assoc default-db :a 1 :b 2)
     ::core/change-history [[[:a] 1] [[:b] 2]]}))

#_
(deftest context-access
  (test-graph-events
    {:action #(+ % 5)}
    default-db
    [{:inputs  [[:a]]
      :outputs [[:b]]
      :handler (fn [ctx {:keys [a]} _] {:b ((:action ctx) a)})}]
    [[[:a] 1]]
    {::core/db             (assoc default-db :a 1 :b 6)
     ::core/change-history [[[:a] 1] [[:b] 6]]}))

#_
(deftest triggering-sub-path
  (test-graph-events
    [{:inputs  [[:h]]
      :outputs [[:h]]
      :handler (fn [ctx {h :h} {old-h :h}]
                 {:h (update old-h :i + (:i h))})}]
    [[[:h :i] 1]]                                           ;; [[[:h] {:i 2}]]
    {::core/db             (assoc default-db :h {:i 2})
     ::core/change-history [[[:h :i] 1]
                            [[:h] {:i 2}]]}))
