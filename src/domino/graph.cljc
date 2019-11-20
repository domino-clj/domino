(ns domino.graph
  (:require
    [domino.model :as model]
    [domino.util :refer [generate-sub-paths]]
    [clojure.set :refer [union]]))

(def conj-set (fnil conj #{}))

(def into-set (fnil into #{}))

(defn find-related
  "finds other nodes related by eventset"
  [input-node events]
  (->> events
       (keep (fn [{:keys [inputs outputs]}]
               (when (some #{input-node} outputs)
                 (remove #(= input-node %) inputs))))
       (apply concat)))

(defn add-nodes
  [ctx inputs]
  (reduce
    (fn [[{:keys [nodes] :as ctx} inputs] input]
      (let [related (find-related input nodes)]
        [(-> ctx
             (update :visited conj-set input)
             (update :graph update input into-set related))
         (into inputs related)]))
    [ctx #{}]
    inputs))

(defn base-graph-ctx
  [nodes]
  {:nodes nodes
   :graph {}})

(defn input-nodes
  [events]
  (distinct (mapcat :inputs events)))

(defn gen-graph
  [events]
  (reduce
    (fn [g {i :inputs o :outputs}]
      (merge-with
        union
        g
        (zipmap i (repeat (set o)))))
    {}
    events))

(defn reverse-edge-direction
  [graph]
  (reduce-kv
    (fn [inverted i o]
      (merge-with
        union
        inverted
        (zipmap o (repeat #{i}))))
    {}
    graph))

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
          (fn [[g errors] {i :inputs o :outputs h :handler :as ev}]
            [(merge-with
               union
               g
               (zipmap i (repeat #{{:edge ev :relationship :input :connections (set o)}}))
               (zipmap o (repeat #{{:edge ev :relationship :output :connections (set i)}})))
             (validate-event ev errors)])
          [{} {}]
          events)]
    (if-not (empty? errors)
      (throw (ex-info
               "graph contains invalid events"
               {:errors errors}))
      graph)))

(defn traversed-edges [origin graph edge-filter]
  (let [edges         (filter edge-filter (get graph origin #{}))
        related-nodes (filter
                        (partial contains? graph)
                        (disj
                          (reduce
                            union
                            #{}
                            (map :connections edges))
                          origin))]
    (apply union
           (set edges)
           (map
             #(traversed-edges
                %
                (dissoc graph origin)
                edge-filter)
             related-nodes))))

(defn connected-nodes-map [graph edge-filter]
  (->> graph
       keys
       (map
         (juxt identity
               #(->> (traversed-edges % graph edge-filter)
                     (map :connections)
                     (apply union))))
       (into {})))

(defn subgraphs [graph]
  (->> (connected-nodes-map graph (constantly true))
       vals
       distinct
       (remove empty?)
       (map #(select-keys graph %))))