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

(deftest trigger-events-test
  (let [ctx (core/initialize {:model  [[:n {:id :n}]
                                         [:m {:id :m}]]
                                :events [{:id      :match-n
                                          :inputs  [:n]
                                          :outputs [:m]
                                          :handler (fn [_ {:keys [n]} _]
                                                     {:m n})}]}
                        {:n 10 :m 0})]
    (is (= {:n 10 :m 10} (:domino.core/db (core/trigger-events ctx [:match-n]))))))

(deftest trigger-events-without-input
  (let [ctx (core/initialize {:model  [[:n {:id :n}]
                                       [:m {:id :m}]]
                              :events [{:id      :match-n
                                        :inputs  []
                                        :outputs [:m :n]
                                        :handler (fn [_ _ {:keys [n]}]
                                                   {:m n})}]}
                             {:n 10 :m 0})]
    (is (= {:n 10 :m 10} (:domino.core/db (core/trigger-events ctx [:match-n]))))))

(deftest trigger-effects-without-input
  (let [ctx (initialize {:model  [[:n {:id :n}]
                                       [:m {:id :m}]]
                              :effects [{:id      :match-n
                                         :outputs [:m :n]
                                         :handler (fn [_ {:keys [n]}]
                                                    {:m n})}]}
                             {:n 10 :m 0})]
    (is (= {:n 10 :m 10} (:domino.core/db (trigger-effects ctx [:match-n]))))))

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
                                             [:baz {:id   :baz}]
                                             [:buz {:id :buz}]]
                                   :events  [{:inputs  [:bar :baz]
                                              :outputs [:buz]
                                              :handler (fn [ctx {:keys [bar baz]} _]
                                                         {:buz (+ bar baz)})}]
                                   :effects [{:inputs  [:buz]
                                              :handler (fn [ctx {:keys [buz]}]
                                                         (reset! result buz))}]})]
    (is (= {:baz 1 :buz 6} (:domino.core/db (core/transact ctx [[[:baz] 1]]))))))
