(ns domino.subcontexts-test
  (:require  #?(:clj [clojure.test :refer :all]
                :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])
             [domino.core :as core]))

;; TODO: Events/constraints across context boundaries

(def test-schema
  ""
  {:model [[:mrn {:id :mrn}]
           [:height
            [:metres {:id :h}]
            [:inches {:id :h-in}]]
           [:weight
            [:kilograms {:id :w}]
            [:pounds    {:id :w-lb}]]
           [:bmi    {:id :bmi}]]
   :events [{:id :event/kg->lb
             :inputs [:w]
             :outputs [:w-lb]
             :ignore-events #{:event/lb->kg}
             :should-run
             (fn [{{:keys [w]} :inputs}]
               (number? w))
             :handler
             (fn [{{:keys [w]}    :inputs
                   {:keys [w-lb]} :outputs}]
               {:w-lb
                ((fnil * 0) w 2.2)})}
            {:id :event/lb->kg
             :inputs [:w-lb]
             :outputs [:w]
             :ignore-events #{:event/kg->lb}
             :should-run
             (fn [{{:keys [w-lb]} :inputs}]
               (number? w-lb))
             :handler
             (fn [{{:keys [w-lb]} :inputs
                   {:keys [w]}    :outputs}]
               {:w ((fnil / 0) w-lb 2.2)})}

            {:id :event/m->in
             :inputs [:h]
             :outputs [:h-in]
             :ignore-events #{:event/in->m}
             :should-run
             (fn [{{:keys [h]} :inputs}]
               (number? h))
             :handler
             (fn [{{:keys [h]} :inputs
                   {:keys [h-in]} :outputs}]
               {:h-in ((fnil / 0) h 0.0254)})}
            {:id :event/in->m
             :inputs [:h-in]
             :outputs [:h]
             :ignore-events #{:event/m->in}
             :should-run
             (fn [{{:keys [h-in]} :inputs}]
               (number? h-in))
             :handler
             (fn [{{:keys [h-in]} :inputs
                   {:keys [h]}    :outputs}]
               {:h ((fnil * 0) h-in 0.0254)})}

            {:id :event/bmi
             :inputs [:h :w]
             :outputs [:bmi]
             :ignore-events #{:event/w}
             :should-run (fn [{{:keys [h w]} :inputs}]
                           (and (number? h) (number? w)))
             :handler (fn [{{:keys [h w]} :inputs}]
                        (if (= 0 h)
                          {:bmi nil}
                          {:bmi (/ w h h)}))}
            {:id :event/w
             :inputs [:h :bmi]
             :ignore-changes [:h]
             :ignore-events #{:event/bmi}
             :outputs [:w]
             :should-run (fn [{{:keys [h bmi]} :inputs}]
                           (and (number? h) (number? bmi)))
             :handler (fn [{{:keys [h bmi]} :inputs}]
                        {:w (* bmi h h)})}]})

(deftest test-schema-baseline
  (let [ctx (core/initialize test-schema {:mrn "2"
                                          :height {:metres 2}
                                          :bmi 20})]
    (is (= [:event/m->in :event/w :event/kg->lb]
           (get-in ctx [::core/transaction-report :event-history])))
    (is (= {:mrn "2"
            :bmi 20
            :height {:metres 2 :inches (/ 2 0.0254)}
            :weight {:kilograms (* 20 2 2) :pounds (* 20 2 2 2.2)}}
           (::core/db ctx)))))

(def single-sub-schema {:model [[:patient
                                 {:id :patient
                                  :schema test-schema}]
                                [:bed {:id :bed}]]})

(deftest test-single-subcontext
  (let [ctx (core/initialize single-sub-schema {:bed "A"
                                                :patient {:mrn "2"
                                                          :height {:metres 2}
                                                          :bmi 20}})]
    ;; TODO: aggregate child transaction report on initialize
    #_(is (= [:event/m->in :event/w :event/kg->lb]
           (get-in ctx [::core/transaction-report])))
    (is (= {:bed "A"
            :patient
            {:mrn "2"
             :bmi 20
             :height {:metres 2 :inches (/ 2 0.0254)}
             :weight {:kilograms (* 20 2 2) :pounds (* 20 2 2 2.2)}}}
           (::core/db ctx)))))

(def coll-sub-schema
  {:model [[:patients
            {:id :patients
             :collection? true
             :index-id :mrn
             :schema test-schema}]
           [:bed {:id :bed}]]})

(deftest test-collection-subcontext
  (let [ctx (core/initialize coll-sub-schema {:bed "A"
                                              :patients {"2"
                                                         {:mrn "2"
                                                          :height {:metres 2}
                                                          :bmi 20}
                                                         "3"
                                                         {:mrn "3"
                                                          :height {:metres 2}
                                                          :bmi 20}}})]
    ;; TODO: aggregate child transaction report on initialize
    #_(is (= [:event/m->in :event/w :event/kg->lb]
             (get-in ctx [::core/transaction-report])))
    (is (= {:bed "A"
            :patients
            {"2"
             {:mrn "2"
              :bmi 20
              :height {:metres 2 :inches (/ 2 0.0254)}
              :weight {:kilograms (* 20 2 2) :pounds (* 20 2 2 2.2)}}
             "3"
             {:mrn "3"
              :bmi 20
              :height {:metres 2 :inches (/ 2 0.0254)}
              :weight {:kilograms (* 20 2 2) :pounds (* 20 2 2 2.2)}}}}
           (::core/db ctx)))))
