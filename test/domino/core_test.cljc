(ns domino.core-test
  (:require
    [domino.core :as core]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(defn ->hex [s]
  #?(:clj  (format "%02x" (int s))
     :cljs (.toString (.charCodeAt s 0) 16)))

(defn init-ctx [state]
  (atom
   (core/initialize
    {:model   [[:user {:id :user}
                [:first-name {:id :fname}]
                [:last-name {:id :lname}]
                [:full-name {:id :full-name}]]
               [:user-hex {:id :user-hex}]]

     :effects [{:inputs  [:fname :lname :full-name]
                :handler (fn [{{:keys [fname lname full-name]} :inputs}]
                           (swap! state assoc
                                  :first-name fname
                                  :last-name lname
                                  :full-name full-name))}
               {:inputs  [:user-hex]
                :handler (fn [{{:keys [user-hex]} :inputs}]
                           (swap! state assoc :user-hex user-hex))}]

     :events  [{:inputs  [:fname :lname]
                :outputs [:full-name]
                :handler (fn [{{:keys [fname lname]} :inputs}]
                           {:full-name (or
                                        (when (and fname lname)
                                          (str lname ", " fname))
                                        fname
                                        lname)})}
               {:inputs  [:user]
                :outputs [:user-hex]
                :handler (fn [{{{:keys [first-name last-name full-name]
                                 :or   {first-name "" last-name "" full-name ""}} :user}
                               :inputs}]
                           {:user-hex (->> (str first-name last-name full-name)
                                           (map ->hex)
                                           (apply str))})}]}
    {})))

(deftest transaction-test
  (let [external-state (atom {})
        ctx            (init-ctx external-state)]
    (swap! ctx core/transact [{:fname "Bob"}])
    (is (= {:first-name "Bob"
            :last-name nil
            :full-name "Bob"
            :user-hex "426f62426f62"}
           @external-state))
    (swap! ctx core/transact [{:lname "Bobberton"}])
    (is (= {:first-name "Bob"
            :last-name "Bobberton"
            :full-name "Bobberton, Bob"
            :user-hex "426f62426f62626572746f6e426f62626572746f6e2c20426f62"}
           @external-state))))

(deftest triggering-parent-test
  (let [result (atom nil)
        ctx    (core/initialize {:model   [[:foo {:id :foo}
                                            [:bar {:id :bar}]]
                                           [:baz {:id :baz}]
                                           [:buz {:id :buz}]]
                                 :events  [{:inputs  [:baz]
                                            :outputs [:bar]
                                            :handler (fn [{{:keys [baz]} :inputs}]
                                                       {:bar (inc baz)})}
                                           {:inputs  [:foo]
                                            :outputs [:buz]
                                            :handler (fn [{{:keys [foo]} :inputs}]
                                                       {:buz (inc (:bar foo))})}]
                                 :effects [{:inputs  [:foo]
                                            :handler (fn [{{:keys [foo]} :inputs}]
                                                       (reset! result foo))}]})]
    (is (= {:baz 1, :foo {:bar 2}, :buz 3}
           (:domino.core/db (core/transact ctx [[::core/set-value :baz 1]]))))
    (is (= {:bar 2} @result))))

(deftest run-events-on-init
  (let [ctx (core/initialize {:model  [[:foo {:id :foo}
                                        [:bar {:id :bar}]]
                                       [:baz {:id :baz}]
                                       [:buz {:id :buz}]]
                              :events [{:inputs  [:baz]
                                        :outputs [:bar]
                                        :handler (fn [{{:keys [baz]} :inputs}]
                                                   {:bar (inc baz)})}
                                       {:inputs  [:foo]
                                        :outputs [:buz]
                                        :handler (fn [{{:keys [foo]} :inputs}]
                                                   {:buz (inc (:bar foo))})}]}
                             {:baz 1})]
    (is (= {:foo {:bar 2} :baz 1 :buz 3} (:domino.core/db ctx)))))

(deftest trigger-effects-test
  (let [ctx (core/initialize {:model   [[:n {:id :n}]
                                        [:m {:id :m}]]
                              :effects [{:id      :match-n
                                         :outputs [:m]
                                         :handler (fn [_]
                                                    {:m 10})}]}
                             {:n 10 :m 0})]
    (is (= {:n 10 :m 10} (:domino.core/db (core/trigger-effects ctx [:match-n]))))))

(deftest trigger-effect-to-update-existing-value
  (let [ctx (core/initialize {:model   [[:total {:id :total}]]
                              :effects [{:id      :increment-total
                                         :outputs [:total]
                                         :handler (fn [{current-state :outputs}]
                                                    (update current-state :total inc))}]}
                             {:total 0})]
    (is (= {:total 1} (:domino.core/db (core/trigger-effects ctx [:increment-total]))))))

(deftest trigger-effects-without-input
  (let [ctx (core/initialize {:model   [[:foo
                                         [:o {:id :o}]
                                         [:p {:id :p}]]
                                        [:n {:id :n}]
                                        [:m {:id :m}]]
                              :effects [{:id      :match-n
                                         :outputs [:m :n]
                                         :handler (fn [{{:keys [n]} :outputs}]
                                                    {:m n})}
                                        {:id      :match-deep
                                         :outputs [:o :p]
                                         :handler (fn [{{:keys [p]} :outputs}]
                                                    {:o p})}]}
                             {:n 10 :m 0 :foo {:p 20}})]
    (is (= {:n 10 :m 10 :foo {:p 20}} (:domino.core/db (core/trigger-effects ctx [:match-n]))))
    (is (= {:n 10 :m 0 :foo {:p 20 :o 20}} (:domino.core/db (core/trigger-effects ctx [:match-deep]))))))


(deftest no-key-at-path
  ;; NOTE: this used to be self-triggering and non-convergent.
  ;;       Add another test for non-convergent events.
  (let [ctx (core/initialize {:model  [[:foo {:id :foo}]
                                       [:bar {:id :bar}]
                                       [:baz {:id :baz}]
                                       [:a {:id :a}]]
                              :events [{:inputs  [:foo :bar]
                                        :outputs [:baz :a]
                                        :handler (fn [{{:keys [foo bar]
                                                        :or {foo :default}
                                                        :as inputs} :inputs}]
                                                   {:baz foo
                                                    :a inputs})}]})]
    (is (= {:bar 1 :baz :default :a {:bar 1}}
           (:domino.core/db (core/transact ctx [{:bar 1}]))))))

(deftest rx-preserved
  (let [ctx (core/initialize {:model  [[:foo {:id :foo}]
                                       [:bar {:id :bar}]
                                       [:baz {:id :baz}]
                                       [:a {:id :a}]]
                              :events [{:inputs  [:foo :bar]
                                        :outputs [:baz :a]
                                        :handler (fn [{{:keys [foo bar]
                                                        :or {foo :default}
                                                        :as inputs} :inputs}]
                                                   {:baz foo
                                                    :a inputs})}]
                              :reactions [{:id :rx/foobar
                                           :args [:foo :bar]
                                           :fn str}]}
                             {:foo 1})
        foobar-pre (core/select ctx :rx/foobar)
        ctx-2 (core/transact ctx [{:foo 2 :bar 2}])
        foobar-pre-2 (core/select ctx :rx/foobar)]
    (is (= foobar-pre foobar-pre-2))
    ))



;; TODO: add tests for new features (see below)
;;  - EVENTS
;;    - :should-run
;;    - :exclusions
;;    - :ignore-changes
;;    - :ignore-events
;;    - :evaluation (:converge, :converge-weak, :once, :first)
;;    - inputs and outputs pre.
;;    - async with :should-run
;;  - CONSTRAINTS
;;  - CHANGES
;;    - Custom Change Map
;;    - ::core/set-value and remove-value
;;    - ::core/update-child and remove-child
;;    - ::core/set
;;    - vector id parsing
;;    - map parsing
;;    - change grouping
;;    - nil pruning
;;    - (once added) ::core/set-db
;;  - INITIALIZATION
;;    - Effect triggering
;;    - Event triggering
;;    - Correct report aggregation
;;  - TRANSACTIONS
;;    - Report keys and aggregation of child reports
;;  - ASYNC
;;  - RELATIONSHIPS
;;    - parent/child?
;;  - EFFECTS
;;    - effect args

;; TODO: Compare to 0.3.3 for (near) parity of use case coverage

;; TODO: Replace interceptor tests with similar use case different impl.
(comment


         (deftest pre-post-interceptors
           ;; TODO: deprecated
           (let [result (atom nil)
                 ctx    (core/initialize {:model   [[:foo {:id  :foo
                                                           :pre [(fn [handler]
                                                                   (fn [ctx inputs outputs]
                                                                     (handler ctx inputs outputs)))
                                                                 (fn [handler]
                                                                   (fn [ctx inputs outputs]
                                                                     (handler ctx inputs outputs)))]}
                                                     [:bar {:id :bar}]]
                                                    [:baz {:id   :baz
                                                           :pre  [(fn [handler]
                                                                    (fn [ctx inputs outputs]
                                                                      (handler ctx inputs outputs)))]
                                                           :post [(fn [handler]
                                                                    (fn [result]
                                                                      (handler (update result :buz inc))))]}]
                                                    [:buz {:id :buz}]]
                                          :events  [{:inputs  [:foo :baz]
                                                     :outputs [:buz]
                                                     :handler (fn [ctx {:keys [baz]} _]
                                                                {:buz (inc baz)})}]
                                          :effects [{:inputs  [:buz]
                                                     :handler (fn [ctx {:keys [buz]}]
                                                                (reset! result buz))}]})]
             (is (= {:baz 1 :buz 3} (:domino.core/db (core/transact ctx [[[:baz] 1]]))))
             (is (= 3 @result))))
         #_
         (deftest interceptor-short-circuit
           ;;TODO: deprecated
           (let [result (atom nil)
                 ctx    (core/initialize {:model   [[:foo {:id  :foo
                                                           :pre [(fn [handler]
                                                                   (fn [ctx {:keys [baz] :as inputs} outputs]
                                                                     (when (> baz 2)
                                                                       (handler ctx inputs outputs))))]}
                                                     ;; returning nil prevents handler execution

                                                     [:bar {:id :bar}]]
                                                    [:baz {:id :baz}]
                                                    [:buz {:id :buz}]]
                                          :events  [{:inputs  [:foo :baz]
                                                     :outputs [:buz]
                                                     :handler (fn [ctx {:keys [baz]} _]
                                                                {:buz (inc baz)})}]
                                          :effects [{:inputs  [:buz]
                                                     :handler (fn [ctx {:keys [buz]}]
                                                                (reset! result buz))}]})]
             (is (= {:baz 1} (:domino.core/db (core/transact ctx [[[:baz] 1]]))))
             (is (nil? @result))))
         #_
         (deftest interceptor-on-parent
           ;;TODO: deprecated
           (let [result (atom nil)
                 ctx    (core/initialize {:model   [[:foo {:id  :foo
                                                           :pre [(fn [handler]
                                                                   (fn [ctx inputs outputs]
                                                                     (handler ctx
                                                                              (assoc inputs :bar 5)
                                                                              outputs)))]}
                                                     [:bar {:id :bar}]]
                                                    [:baz {:id :baz}]
                                                    [:buz {:id :buz}]]
                                          :events  [{:inputs  [:bar :baz]
                                                     :outputs [:buz]
                                                     :handler (fn [ctx {:keys [bar baz]} _]
                                                                {:buz (+ bar baz)})}]
                                          :effects [{:inputs  [:buz]
                                                     :handler (fn [ctx {:keys [buz]}]
                                                                (reset! result buz))}]})]
             (is (= {:baz 1 :buz 6} (:domino.core/db (core/transact ctx [[[:baz] 1]])))))))
