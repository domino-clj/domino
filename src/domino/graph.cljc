(ns domino.graph
  (:require
    [clojure.set :refer [union]]))

(defn validate-event [{:keys [outputs handler] :as ev} errors]
  (if-let [event-errors (not-empty
                          (cond-> []
                                  (empty? outputs) (conj "event :outputs must contain at least one target")
                                  (not (fn? handler)) (conj "event :handler must be a function")))]
    (assoc errors ev event-errors)
    errors))

(defn gen-ev-graph
  [events]
  (let [[graph errors]
        (reduce
          (fn [[g errors] {i :inputs o :outputs :as ev}]
            [(merge-with
               union
               g
               (zipmap i (repeat #{{:edge ev :relationship :input :connections (set o)}}))
               (zipmap o (repeat #{{:edge ev :relationship :output :connections (set i)}})))
             (validate-event ev errors)])
          [{} {}]
          events)]
    (if (seq errors)
      (throw (ex-info
               "graph contains invalid events"
               {:errors errors}))
      graph)))
