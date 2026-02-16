(ns domino.core-test
  (:require
    [domino.core :as core]
    [domino.effects]
    [domino.graph]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

(defn ->hex [s]
  #?(:clj  (format "%02x" (int s))
     :cljs (.toString (.charCodeAt s 0) 16)))

(defn init-ctx [state]
  (atom
    (core/initialize {:model   [[:user {:id :user}
                                 [:first-name {:id :fname}]
                                 [:last-name {:id :lname}]
                                 [:full-name {:id :full-name}]]
                                [:user-hex {:id :user-hex}]]

                      :effects [{:inputs  [:fname :lname :full-name]
                                 :handler (fn [_ {:keys [fname lname full-name]}]
                                            (swap! state assoc
                                                   :first-name fname
                                                   :last-name lname
                                                   :full-name full-name))}
                                {:inputs  [:user-hex]
                                 :handler (fn [_ {:keys [user-hex]}]
                                            (swap! state assoc :user-hex user-hex))}]

                      :events  [{:inputs  [:fname :lname]
                                 :outputs [:full-name]
                                 :handler (fn [_ {:keys [fname lname]} _]
                                            {:full-name (or (when (and fname lname) (str lname ", " fname))
                                                            fname
                                                            lname)})}
                                {:inputs  [:user]
                                 :outputs [:user-hex]
                                 :handler (fn [_ {{:keys [first-name last-name full-name]
                                                   :or   {first-name "" last-name "" full-name ""}} :user} _]
                                            {:user-hex (->> (str first-name last-name full-name)
                                                            (map ->hex)
                                                            (apply str))})}]}
                     {})))

(deftest transaction-test
  (let [external-state (atom {})
        ctx            (init-ctx external-state)]
    (swap! ctx core/transact [[[:user :first-name] "Bob"]])
    (is (= {:first-name "Bob" :last-name nil :full-name "Bob" :user-hex "426f62426f62"} @external-state))
    (swap! ctx core/transact [[[:user :last-name] "Bobberton"]])
    (is (= {:first-name "Bob" :last-name "Bobberton" :full-name "Bobberton, Bob" :user-hex "426f62426f62626572746f6e426f62626572746f6e2c20426f62"} @external-state))))

(deftest triggering-parent-test
  (let [result (atom nil)
        ctx    (core/initialize {:model   [[:foo {:id :foo}
                                            [:bar {:id :bar}]]
                                           [:baz {:id :baz}]
                                           [:buz {:id :buz}]]
                                 :events  [{:inputs  [:baz]
                                            :outputs [:bar]
                                            :handler (fn [ctx {:keys [baz]} _]
                                                       {:bar (inc baz)})}
                                           {:inputs  [:foo]
                                            :outputs [:buz]
                                            :handler (fn [ctx {:keys [foo]} _]
                                                       {:buz (inc (:bar foo))})}]
                                 :effects [{:inputs  [:foo]
                                            :handler (fn [ctx {:keys [foo]}]
                                                       (reset! result foo))}]})]
    (is (= {:baz 1, :foo {:bar 2}, :buz 3} (:domino.core/db (core/transact ctx [[[:baz] 1]]))))
    (is (= {:bar 2} @result))))

(deftest run-events-on-init
  (let [ctx (core/initialize {:model  [[:foo {:id :foo}
                                        [:bar {:id :bar}]]
                                       [:baz {:id :baz}]
                                       [:buz {:id :buz}]]
                              :events [{:inputs  [:baz]
                                        :outputs [:bar]
                                        :handler (fn [ctx {:keys [baz]} _]
                                                   {:bar (inc baz)})}
                                       {:inputs  [:foo]
                                        :outputs [:buz]
                                        :handler (fn [ctx {:keys [foo]} _]
                                                   {:buz (inc (:bar foo))})}]}
                             {:baz 1})]
    (is (= {:foo {:bar 2} :baz 1 :buz 3} (:domino.core/db ctx)))))

(deftest trigger-effects-test
  (let [ctx (core/initialize {:model   [[:n {:id :n}]
                                        [:m {:id :m}]]
                              :effects [{:id      :match-n
                                         :outputs [:m]
                                         :handler (fn [_ _]
                                                    {:m 10})}]}
                             {:n 10 :m 0})]
    (is (= {:n 10 :m 10} (:domino.core/db (core/trigger-effects ctx [:match-n]))))))

(deftest trigger-effect-to-update-existing-value
  (let [ctx (core/initialize {:model   [[:total {:id :total}]]
                              :effects [{:id      :increment-total
                                         :outputs [:total]
                                         :handler (fn [_ current-state]
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
                                         :handler (fn [_ {:keys [n]}]
                                                    {:m n})}
                                        {:id      :match-deep
                                         :outputs [:o :p]
                                         :handler (fn [_ {:keys [p]}]
                                                    {:o p})}]}
                             {:n 10 :m 0 :foo {:p 20}})]
    (is (= {:n 10 :m 10 :foo {:p 20}} (:domino.core/db (core/trigger-effects ctx [:match-n]))))
    (is (= {:n 10 :m 0 :foo {:p 20 :o 20}} (:domino.core/db (core/trigger-effects ctx [:match-deep]))))))

(deftest pre-post-interceptors
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

(deftest interceptor-short-circuit
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

(deftest interceptor-on-parent
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
    (is (= {:baz 1 :buz 6} (:domino.core/db (core/transact ctx [[[:baz] 1]]))))))

(deftest trigger-effects-nonexistent-id
  (let [ctx (core/initialize {:model   [[:a {:id :a}]]
                              :effects [{:id      :real
                                         :outputs [:a]
                                         :handler (fn [_ _] {:a 1})}]}
                             {:a 0})]
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #"no effect found"
          (core/trigger-effects ctx [:nonexistent])))))

(deftest no-key-at-path
  (let [ctx (core/initialize {:model  [[:foo {:id :foo}]
                                       [:bar {:id :bar}]
                                       [:baz {:id :baz}]]
                              :events [{:inputs  [:foo :bar]
                                        :outputs [:baz]
                                        :handler (fn [ctx {:keys [foo bar] :or {foo :default}} _]
                                                   {:bar (inc bar) :baz foo})}]})]
    (:domino.core/db (core/transact ctx [[[:bar] 1]]))
    (is (= {:bar 2 :baz :default} (:domino.core/db (core/transact ctx [[[:bar] 1]]))))))

(deftest transaction-report-success
  (let [ctx (core/initialize {:model  [[:a {:id :a}]
                                       [:b {:id :b}]]
                              :events [{:inputs  [:a]
                                        :outputs [:b]
                                        :handler (fn [_ {:keys [a]} _] {:b (inc a)})}]}
                             {:a 0 :b 0})
        result (core/transact ctx [[[:a] 1]])]
    (is (= :complete (get-in result [::core/transaction-report :status])))
    (is (vector? (get-in result [::core/transaction-report :changes])))))

(deftest transaction-report-failure
  (let [ctx (core/initialize {:model  [[:a {:id :a}]
                                       [:b {:id :b}]]
                              :events [{:inputs  [:a]
                                        :outputs [:b]
                                        :handler (fn [_ {:keys [a]} _]
                                                   (if (= a 99)
                                                     (throw (ex-info "boom" {:id :test-error}))
                                                     {:b (inc a)}))}]}
                             {:a 0 :b 0})]
    (try
      (core/transact ctx [[[:a] 99]])
      (is false "should have thrown")
      (catch #?(:clj Exception :cljs js/Error) e
        (let [report (::core/transaction-report (ex-data e))]
          (is (= :failed (:status report)))
          (is (some? (:message report))))))))
