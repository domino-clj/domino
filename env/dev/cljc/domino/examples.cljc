(ns domino.examples)

(def full-name-constraint
  {:id :compute-full-name
   :query {:f :given-name
           :l :surname
           :n :full-name}
   :return {:n :full-name}
   :pred (fn [{:keys [f l n]}]
           (= (if (or (empty? f) (empty? l))
                (or (not-empty f) (not-empty l))
                (str l ", " f))
              n))
   :resolver (fn [{:keys [f l n]}]
               {:n (if (or (empty? f) (empty? l))
                     (or (not-empty f) (not-empty l))
                     (str l ", " f))})})

(def example-schema-trivial
  ""
  {:model [[:mrn {:id :mrn}]
           [:name
            [:first {:id :given-name}]
            [:last  {:id :surname}]
            [:full {:id :full-name}]]
           [:height
            [:metres {:id :h :val-if-missing 0}]
            [:inches {:id :h-in :val-if-missing 0}]]
           [:weight
            [:kilograms {:id :w :val-if-missing 0}]
            [:pounds    {:id :w-lb :val-if-missing 0}]]
           [:bmi    {:id :bmi}]
           [:async
            [:in {:id :in}]
            [:out {:id :out}]
            [:ms {:id :ms :val-if-missing 1000}]]]
   :constraints [full-name-constraint]
   :events [{:inputs [:w]
             :outputs [:w-lb]
             :handler
             (fn [{{:keys [w]} :inputs
                   {:keys [w-lb]} :outputs}]
               (when-not (< -0.01 (- (* w 2.2) w-lb) 0.01)
                 {:w-lb
                  (* w 2.2)}))}
            {:id :event/convert-w-lb
             :inputs [:w-lb]
             :outputs [:w]
             :handler
             (fn [{{:keys [w-lb]} :inputs
                   {:keys [w]}    :outputs}]
               (when-not (< -0.01 (- (* w 2.2) w-lb) 0.01)
                 {:w (/ w-lb 2.2)}))}
            {:id :event/convert-h
             :inputs [:h]
             :outputs [:h-in]
             :handler
             (fn [{{:keys [h]} :inputs
                   {:keys [h-in]} :outputs}]
               (when-not (< -0.01 (- (* h-in 0.0254) h) 0.01)
                   {:h-in (/ h 0.0254)}))}
            {:id :event/convert-h-in
             :inputs [:h-in]
             :outputs [:h]
             :handler
             (fn [{{:keys [h-in]} :inputs
                   {:keys [h]} :outputs}]
               (when-not (< -0.01 (- (* h-in 0.0254) h) 0.01)
                 {:h (* h-in 0.0254)}))}
            {:id :event/bmi
             :inputs [:h :w]
             :outputs [:bmi]
             :handler (fn [{{:keys [h w]} :inputs}]
                        (if (= 0 h)
                          {:bmi nil}
                          {:bmi (/ w h h)}))}
            {:id :event/w
             :inputs [:h :bmi]
             :ignore-changes [:h]
             :outputs [:w]
             :handler (fn [{{:keys [h bmi]} :inputs}]
                        {:w (* bmi h h)})}]
   :effects
   [{:id :fx/print-bmi
     :inputs [:bmi]
     :handler (fn [{{:keys [bmi]} :inputs}]
                (println "\n\n\n\n\nBMI:")
                (println bmi)
                (println "\n\n\n\n\n"))}
    {:id :fx/set-patient
     :outputs [:given-name :surname :h :w :in :ms]
     :handler (fn [{{:keys [f l h w in ms] :as args} :args}]
                (println args)
                {:given-name (or f "John")
                 :surname (or l "Doe")
                 :h (or h 1.85)
                 :w (or w 200)
                 :in (or in 40)
                 :ms (or ms 500)})}]})

(def example-schema-async (update example-schema-trivial :events conj
                                  {:id :event/set-out
                                   :inputs [:in :ms]
                                   :ignore-changes [:ms]
                                   :outputs [:out]
                                   :async? true
                                   :should-run (fn [{{:keys [in]} :inputs {:keys [out]} :outputs}]
                                                 (or (not= in out) (println "Skipping..." in out)))
                                   :handler (fn [{{:keys [in ms]} :inputs {:keys [out]} :outputs} cb]
                                              (println "in: " in "ms: " ms)
                                              #?(:clj
                                                 (future
                                                   (println "Sleeping for ms: " ms)
                                                   (Thread/sleep ms)
                                                   (println "WOKE UP!")
                                                   (cb {:out in}))
                                                 :cljs
                                                 (js/setTimeout
                                                  #(cb {:out in})
                                                  ms)))}))


(def example-schema-nested
    "
  This schema is an example of non-collection nesting.
  This allows you to defer to the inner schema of `:patient` for accessing its contents.
  For example:
  (transact <instance> [[[:patient :surname] \"Anderson\"] [:bed-id \"122-A\"] [[:patient :given-name] \"Mr.\"]])
  is equivalent to:
  (update <instance> ::db
          #(-> %
              (update-in [:patient] transact [[:surname \"Anderson\"]])
              (assoc-in  [:bed] \"122-A\")
              (update-in [:patient] transact [[:surname \"Mr.\"]])))

  (transact <instance [[:patient {:mrn \"1234123\" :name {:first \"Mr.\" :last \"Anderson\"}}]])

  (select <instance> [:patient :surname])
  is equivalent to:
  (select (get-in <instance> [::db :patient]) :surname)
  "
    {:model [[:bed {:id :bed-id}]
             [:stay
              [:start {:id :start}]
              [:end   {:id :end}]]
             [:label {:id :label}]
             [:patient {:id :patient
                        :schema
                        example-schema-trivial}]]})

(def example-schema-1
    ""
    {:model
     [[:patients
       {:id :patients
        :index-id :mrn
        :order-by [:surname :given-name :mrn]
        :collection? true
        :schema
        example-schema-trivial}]
      [:shift
       [:start {:id :start-date}]
       [:end {:id :end-date}]
       [:length {:id :shift-length}]]]
     #_#_:events [{:inputs [:shift-length [:patient :medication :dose]] ;; [:patients "1234123" :medications "224-A" :dose]
               :outputs [[:patient :medication :unit]]}]})

(def example-schema-2
    ""
    {:model
     [[:patients
       {:id :patients
        :index-id :mrn
        :order-by [:surname :given-name :mrn]
        :collection? true
        :schema
        {:constraints [full-name-constraint]
         :model
         [[:mrn {:id :mrn}]
          [:name
           [:first {:id :given-name}]
           [:last {:id :surname}]
           [:full {:id :full-name}]]
          [:risk {:id :risk}]
          [:medications
           {:id :medications
            :index-id :rx-id
            :order-by [:creation-date :rx-id]
            :collection? true
            :schema
            {:model
             [[:rx-id {:id :rx-id}]
              [:creation-date {:id :creation-date}]
              [:name {:id :drug}]
              [:dose {:id :dose}]
              [:unit {:id :unit}]]}}]]}}]
      [:shift
       [:start {:id :start-date}]
       [:end {:id :end-date}]
       [:length {:id :shift-length}]]]})
