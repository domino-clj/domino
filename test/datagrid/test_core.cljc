(ns datagrid.test-core
  (:require
    [datagrid.graph :refer :all]
    [cljs.test :refer-macros [is are deftest testing use-fixtures]]))

(defmacro is= [v f]
  `(is (= ~v ~f)))

(deftest empty-events
  (testing "running without events should return only the original changes")
  (is=
    [{:path [:patient :height :cm] :value 190}]
    (let [events      nil
          doc         {:patient
                       {:height {:cm 180}
                        :weight {:kg 70}}}
          event-graph (connect events)
          change      {:path [:patient :height :cm] :value 190}]
      (transact
        {:document doc}
        event-graph
        change))))

(deftest event-with-errors
  (testing "running a event with errors should throw those errors")
  (is
    (thrown-with-msg? Exception #"event: :bad-event failed to run"
                      (let [events      [{:id      :bad-event
                                          :type    :action
                                          :inputs  [[:patient :age :years]]
                                          :outputs [[:patient :born :year]]
                                          :fn      (fn [ctx [age-in-years] [born-year]]
                                                     [(/ 1 0)])}]
                            doc         {:patient
                                         {:age    70
                                          :height {:cm 180}
                                          :weight {:kg 70}}}
                            event-graph (connect events)
                            change      {:path [:patient :age :years] :value 90}]
                        (transact
                          {:document doc}
                          event-graph
                          change)))))

(deftest no-matching-events
  (testing "running without any matching events should return only the original changes")
  (is=
    [{:path [:patient :height :cm] :value 190}]
    (let [events      [{:type    :action
                        :inputs  [[:patient :age :years]]
                        :outputs [[:patient :born :year]]
                        :fn      (fn [ctx [age-in-years] [born-year]]
                                   (throw (Exception. "event should not run")))}]
          doc         {:patient
                       {:age    70
                        :height {:cm 180}
                        :weight {:kg 70}}}
          event-graph (connect events)
          change      {:path [:patient :height :cm] :value 190}]
      (transact
        {:document doc}
        event-graph
        change))))

(deftest event-returning-a-nil
  (testing "events returning a nil should return the original value of the event's output")
  (is=
    [{:path [:patient :height :cm] :value 190}
     {:path [:patient :height :inch] :value 72}]
    (let [events      [{:type    :action
                        :inputs  [[:patient :height :cm]]
                        :outputs [[:patient :height :inch]]
                        :fn      (fn [_ _ _])}]
          doc         {:patient
                       {:age    70
                        :height {:cm 180 :inch 72}
                        :weight {:kg 70}}}
          event-graph (connect events)
          change      {:path [:patient :height :cm] :value 190}]
      (transact
        {:document doc}
        event-graph
        change))))

(deftest independent-events
  (testing "all related events are run & unrelated events are not run")
  (is=
    [{:path [:patient :height :cm], :value 190}
     {:path [:patient :height :inch], :value 74.80314960629921}
     {:path [:metadata], :value "cm value is: 190"}]
    (let [events      [{:id      :height-inch-event
                        :type    :action
                        :inputs  [[:patient :height :cm]]
                        :outputs [[:patient :height :inch]]
                        :fn      (fn [ctx [height-cm] [height-inch]]
                                   [(/ height-cm 2.54)])}

                       {:id      :cm-triggered-event
                        :type    :action
                        :inputs  [[:patient :height :cm]]
                        :outputs [[:metadata]]
                        :fn      (fn [ctx [height-cm] [metadata]]
                                   [(str "cm value is: " height-cm)])}

                       {:id      :weight-event
                        :type    :action
                        :inputs  [[:patient :weight :kg]
                                  [:patient :weight :lb]]
                        :outputs [[:patient :weight :kg]
                                  [:patient :weight :lb]]
                        :fn      (fn [ctx [weight-kg weight-lb] [weight-kg weight-lb]]
                                   (throw (Exception. "weight event should not run")))}]
          doc         {:patient
                       {:age    70
                        :height {:cm   180
                                 :inch 70.8}
                        :weight {:kg 70}}}
          event-graph (connect events)
          change      {:path [:patient :height :cm] :value 190}]
      (transact
        {:document doc}
        event-graph
        change))))

(deftest events-with-context
  (testing "events should be able to access and modify context")
  (is=
    [{:path [:context-info] :value {:action :born-year-set :events/applied [:update-age]}}]
    (let [events      [{:id      :update-age
                        :type    :action
                        :inputs  [[:context-info]]
                        :outputs [[:context-info]]
                        :fn      (fn [ctx _ [info]]
                                   {:event/context (assoc ctx :action :born-year-set)
                                    :event/outputs [:event1]})}
                       {:id      :update-stuff
                        :type    :action
                        :inputs  [[:context-info]]
                        :outputs [[:context-info]]
                        :fn      (fn [ctx _ [info]]
                                   [(select-keys ctx [:action :events/applied])])}]
          doc         {:patient
                       {:age    70
                        :height {:cm 180}
                        :weight {:kg 70}}}
          event-graph (connect events)
          change      {:path [:context-info] :value :new-value}]
      (transact
        {:document doc}
        event-graph
        change))))

(deftest event-not-triggered
  (testing "event is not triggered because input hasn't changed"
    (is=
      [{:path [:patient :weight :kg], :value 440}]
      (let [events      [{:id      :height-inch-event
                          :type    :action
                          :inputs  [[:patient :height :inch]]
                          :outputs [[:patient :height :cm]]
                          :fn      (fn [ctx [height-inch] [height-cm]]
                                     (throw (Exception. "height event should not be triggered")))}
                         {:id      :bmi-event
                          :type    :action
                          :inputs  [[:patient :weight :kg]
                                    [:patient :height :cm]]
                          :outputs [[:patient :bmi]]
                          :fn      (fn [ctx [weight height] [bmi-out]]
                                     (throw (Exception. "bmi event should not be triggered")))}]
            doc         {:patient {:height {:cm 30 :inch 190}, :weight {:kg 440}, :bmi nil}}
            event-graph (connect events)
            change      {:path [:patient :weight :kg] :value 440}]
        ;event-graph
        (transact
          {:document doc}
          event-graph
          change)))))

(deftest event-triggered-by-output-path
  (testing "events chain from output paths"
    (is=
      [{:path [:patient :height :inch], :value 190}
       {:path [:patient :height :cm], :value 482.6}
       {:path [:patient :bmi], :value 20.72109407376709}]
      (let [events      [{:id      :height-inch-event
                          :type    :action
                          :inputs  [[:patient :height :inch]]
                          :outputs [[:patient :height :cm]]
                          :fn      (fn [ctx [height-inch] [height-cm]]
                                     [(* height-inch 2.54)])}
                         {:id      :bmi-event
                          :type    :action
                          :inputs  [[:patient :weight :kg]
                                    [:patient :height :cm]]
                          :outputs [[:patient :bmi]]
                          :fn      (fn [ctx [weight height] [bmi-out]]
                                     [(bmi height height)])}]
            doc         {:patient {:height {:inch 180}, :weight {:kg 440}, :bmi nil}}
            event-graph (connect events)
            change      {:path [:patient :height :inch] :value 190}]
        ;event-graph
        (transact
          {:document doc}
          event-graph
          change)))))

(deftest event-cycles
  (testing "testing that event cycles evaluate exactly once"
    (let [events      [{:id      :inch-height-event
                        :type    :action
                        :inputs  [[:patient :height :cm]]
                        :outputs [[:patient :height :inch]]
                        :fn      (fn [ctx [height-cm] [height-inch]]
                                   [(/ height-cm 2.54)])}
                       {:id      :cm-height-event
                        :type    :action
                        :inputs  [[:patient :height :inch]]
                        :outputs [[:patient :height :cm]]
                        :fn      (fn [ctx [height-inch] [height-cm]]
                                   [(* height-inch 2.54)])}
                       {:id      :bmi-event
                        :type    :action
                        :inputs  [[:patient :weight :kg]
                                  [:patient :height :cm]]
                        :outputs [[:patient :bmi]]
                        :fn      (fn [ctx [weight height] [bmi-out]]
                                   [(bmi weight height)])}]
          doc         {:patient
                       {:height {:cm 180 :inch 72}
                        :weight {:kg 70}}}
          event-graph (connect events)
          change      {:path [:patient :height :inch] :value 190}]
      ;event-graph
      (transact
        {:document doc}
        event-graph
        change))))

(deftest test-event-returning-a-nil
  (testing "returning [nil] from a event"
    (is=
      [{:path [:patient :height :cm], :value nil} {:path [:patient :height :inch], :value nil}]
      (let [events      [{:id      :inch-height-event
                          :type    :action
                          :inputs  [[:patient :height :cm]]
                          :outputs [[:patient :height :inch]]
                          :fn      (fn [ctx [height-cm] [height-inch]]
                                     [(when height-cm (/ height-cm 2.54))])}
                         {:id      :cm-height-event
                          :type    :action
                          :inputs  [[:patient :height :inch]]
                          :outputs [[:patient :height :cm]]
                          :fn      (fn [ctx [height-inch] [height-cm]]
                                     [(when height-inch (* height-inch 2.54))])}]
            doc         {:patient
                         {:height {:cm 180 :inch 72}}}
            event-graph (connect events)
            change      {:path [:patient :height :cm] :value nil}]
        ;event-graph
        (transact
          {:document doc}
          event-graph
          change)))))

(deftest test-find-related-paths
  (testing "find-related-paths correctly finds connected subgraph"
    (is=
      #{:a :b :c :e :f :d}
      (let [events [{:inputs  [:a]
                     :outputs [:b :c]}
                    {:inputs  [:b]
                     :outputs [:d]}
                    {:inputs  [:e]
                     :outputs [:f :a]}]]
        (find-related-paths :a events)))))

(deftest test-group-paths-by-related-subgraph
  (testing "group-paths-by-related-subgraph"
    (let [event->related-paths {:event1 [:a :b :c]
                                :event2 [:d :f]
                                :event3 [:u :v :e]
                                :event4 [:e :g :a]
                                :event5 [:x :y :z]}
          events               (keys event->related-paths)]
      (is=
        #{#{:a :b :c :e :g :u :v} #{:d :f} #{:x :y :z}}
        (set (group-paths-by-related-subgraph event->related-paths events))))))

(deftest events-test
  (testing "chained events are run in order")
  (is=
    [{:path [:patient :height :cm], :value 190}
     {:path [:patient :height :inch], :value 74.80314960629921}
     {:path [:patient :bmi], :value 7000/361}]
    (let [events      [{:id      :height-event
                        :type    :action
                        :inputs  [[:patient :height :cm]]
                        :outputs [[:patient :height :inch]]
                        :fn      (fn [ctx [height-cm] [height-inch]]
                                   [(/ height-cm 2.54)])}
                       {:id      :bmi-event
                        :type    :action
                        :inputs  [[:patient :weight :kg]
                                  [:patient :height :cm]]
                        :outputs [[:patient :bmi]]
                        :fn      (fn [ctx [weight height] [bmi-out]]
                                   [(bmi weight height)])}]
          doc         {:patient
                       {:height {:cm 180}
                        :weight {:kg 70}}}
          event-graph (connect events)
          change      {:path [:patient :height :cm] :value 190}]
      ;event-graph
      (transact
        {:document doc}
        event-graph
        change))))
