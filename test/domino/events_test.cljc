(ns domino.events-test
  (:require
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])
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

(defn test-events-on-db-rhs [db events changes]
  (->
   test-schema
   (assoc :events events)
   (core/initialize db)
   (core/transact changes)
   (select-keys [::core/db
                 ::core/transaction-report])
   (update ::core/transaction-report
           select-keys
           [:status
            :changes
            :event-history
            :reason
            :message
            :data])
   (update-in [::core/transaction-report :changes]
              (partial mapv :change))))

(defn test-events-on-db [db events changes result]
  (is
   (= result
      (test-events-on-db-rhs db events changes))))

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
  (let [error (ex-info "Foo" {:test :error})]
    (is
     (thrown?
      clojure.lang.ExceptionInfo
      (test-events-on-db-rhs
       (dissoc default-db :a)
       [{:inputs [:a]
         :outputs [:b]
         :handler (fn [{inputs :inputs}]
                    (throw error))}]
       [{:a 1}])))))


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
  (test-events-on-db
   (dissoc default-db :a) ;;prevent eval on initialize
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
  (test-events-on-db
   (dissoc default-db :a :b) ;; Prevent events from running on initialize
   [{:id :first
     :evaluation :once
     :inputs  [:a]
     :outputs [:b :c]
     :handler (fn [{{:keys [a]} :inputs
                    {:keys [b c] :or {b 0}} :outputs}]
                {:b (inc b) :c c})}
    {:id :second
     :evaluation :once
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
     :evaluation :once
     :inputs  [:a]
     :outputs [:b :c]
     :handler (fn [{{:keys [a]} :inputs
                    {:keys [b c]} :outputs}]
                {:b (+ a b) :c c})}
    {:id :second
     :evaluation :once
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

(deftest test-cascading-events
  (test-events
   [{:id :first
     :inputs  [:a]
     :outputs [:b :c]
     :handler (fn [{{:keys [a]} :inputs {:keys [b c]} :outputs}]
                {:b (+ a b) :c (+ a c)})}
    {:id :second
     :inputs  [:c]
     :outputs [:d]
     :handler (fn [{{:keys [c]} :inputs}]
                {:d (inc c)})}]
   [{:a 1 :b 1}]
   {::core/db             (assoc default-db :a 1 :b 2 :c 1 :d 2)
    ::core/transaction-report {:status :complete
                               :changes
                               [[::core/set-value :a 1]
                                [::core/set-value :b 1]
                                [::core/set-value :b 2]
                                [::core/set-value :c 1]
                                [::core/set-value :d 2]]
                               :event-history [:first :second]}}))


(deftest multi-input-event
  (test-events
   [{:id :ev
     :inputs  [:a :b]
     :outputs [:c]
     :handler (fn [{{:keys [a b]} :inputs {:keys [c]} :outputs}] {:c (+ a b)})}]
   [[:a 1] [:b 1]]
   {::core/db             (assoc default-db :a 1 :b 1 :c 2)
    ::core/transaction-report {:status :complete
                               :changes [[::core/set-value :a 1]
                                         [::core/set-value :b 1]
                                         [::core/set-value :c 2]]
                               :event-history [:ev]}}))


(deftest multi-output-event
  (test-events
   [{:id :ev
     :inputs  [:a]
     :outputs [:b :c]
     :handler (fn [{{:keys [a]} :inputs {:keys [b c]} :outputs}]
                {:b (+ a b) :c (+ a c)})}]
   [[:a 1]]
    {::core/db             (assoc default-db :a 1 :b 1 :c 1)
     ::core/transaction-report {:status :complete
                                :changes [[::core/set-value :a 1]
                                          [::core/set-value :b 1]
                                          [::core/set-value :c 1]]
                                :event-history [:ev]}}))

(deftest multi-input-output-event-omitted-unchanged-results
  (test-events
   [{:id :ev
     :inputs  [:a :b]
     :outputs [:c :d :e]
     :handler (fn [{{:keys [a b]} :inputs}] {:c (+ a b)})}]
    [[:a 1] [:b 1]]
    {::core/db             (assoc default-db :a 1 :b 1 :c 2)
     ::core/transaction-report {:status :complete
                                :changes [[::core/set-value :a 1]
                                          [::core/set-value :b 1]
                                          [::core/set-value :c 2]]
                                :event-history [:ev]}}))


(deftest multi-input-output-event
  (test-events
   [{:id :ev
     :inputs  [:a :b]
     :outputs [:c :d :e]
     :handler (fn [{{:keys [a b]} :inputs {:keys [d e]} :outputs}] {:c (+ a b) :d d :e e})}]
   [[:a 1] [:b 1]]
   {::core/db             (assoc default-db :a 1 :c 2 :b 1)
    ::core/transaction-report {:status :complete
                               :changes [[::core/set-value :a 1]
                                         [::core/set-value :b 1]
                                         [::core/set-value :c 2]]
                               :event-history [:ev]}}))


(deftest unrelated-events
  (test-events-on-db
   (dissoc default-db :c)
   [{:id :ev
     :inputs  [:a]
     :outputs [:b]
     :handler (fn [{{:keys [a]} :inputs}] {:b (inc a)})}
    {:id :THIS.SHOULD.NOT/RUN
     :inputs  [:c]
     :outputs [:d]
     :handler (fn [{{:keys [c]} :inputs}] {:d (dec c)})}]
   [[:a 1]]
   {::core/db             (assoc (dissoc default-db :c) :a 1 :b 2)
    ::core/transaction-report {:status :complete
                               :changes [[::core/set-value :a 1]
                                         [::core/set-value :b 2]]
                               :event-history [:ev]}}))

;; TODO: Improve context access pattern.
;; TODO: Allow for initial event context on initialize.
(deftest context-access
  (is
   (= {::core/db             (assoc default-db :a 1 :b 6)
       ::core/transaction-report {:status :complete
                                  :changes [{:change
                                             [::core/set-value :a 1]
                                             :id :a
                                             :status :complete}
                                            {:change
                                             [::core/set-value :b 6]
                                             :id :b
                                             :status :complete}]
                                  :event-history [:ev]}}
      (->
       test-schema
       (assoc :events [{:id :ev
                        :ctx-args [:action]
                        :inputs  [:a]
                        :outputs [:b]
                        :handler (fn [{{:keys [a]} :inputs
                                       {:keys [action]} :ctx-args}] {:b (action a)})}])
       (core/initialize (dissoc default-db :a))
       (update ::core/event-context (fnil assoc {}) :action #(+ % 5))
       (core/transact [[:a 1]])
       (select-keys [::core/db
                     ::core/transaction-report])))))

(deftest triggering-sub-path
  (test-events
   [{:id :ev
     :inputs  [:h]
     :outputs [:h]
     :evaluation :once
     :handler (fn [{{h :h} :inputs {old-h :h} :outputs}]
                {:h (update old-h :i + (:i h))})}]
   [[:i 1]]                                           ;; [[[:h] {:i 2}]]
   {::core/db             (assoc default-db :h {:i 2})
    ::core/transaction-report {:changes [[::core/set-value :i 1]
                                         [::core/set-value :h {:i 2}]]
                               :event-history [:ev]
                               :status :complete}}))

;; TODO: Events across context boundaries
