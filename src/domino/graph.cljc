(ns domino.graph
  (:require
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


;;;
(defn get-db-paths [db paths]
  (map #(get-in db %) paths))

(def empty-queue
  #?(:clj  clojure.lang.PersistentQueue/EMPTY
     :cljs cljs.core/PersistentQueue.EMPTY))

#?(:clj
   (defmethod clojure.core/print-method clojure.lang.PersistentQueue
     [queue writer]
     (.write writer
             (str "#<PersistentQueue: " (pr-str (vec queue)) ">"))))

(defn try-event [{:keys [handler inputs] :as event} ctx db old-outputs]
  (try
    (or
      (handler ctx (get-db-paths db inputs) old-outputs)
      old-outputs)
    (catch #?(:clj Exception :cljs js/Error) e
      (throw (ex-info "failed to execute event" {:event event :context ctx :db db} e)))))

(defn build-change-paths
  [path]
  (loop [paths []
         path path]
    (if (not-empty path)
      (recur (conj paths (vec path)) (drop-last path))
      paths)))

(defn ctx-updater
  "Reducer that updates context with new values updated in ctx from
  handler of each edge. New values are only stored when they are different
  from old values.

  In changed cases, the following keys are updated:
  ::changed-paths => queue of affected paths
  ::db => temporary relevant db within context
  ::change-history => sequential history of changes. List of tuples of path-value pairs"
  [edges {::keys [db executed-events] :as ctx}]
  (reduce
    (fn [ctx {{:keys [inputs outputs handler] :as event} :edge}]
      (if (contains? executed-events event)
        ctx
        (let [ctx         (update ctx ::executed-events conj event)
              old-outputs (get-db-paths db outputs)
              new-outputs (try-event event ctx db old-outputs)]
          (when-not (= (count outputs) (count new-outputs))
            (throw
              (ex-info "number of outputs returned by the handler must match the number of declared outputs"
                       {:declared-outputs outputs
                        :outputs          new-outputs})))
          (reduce
            (fn [ctx [path old new]]
              (if (not= old new)
                (-> ctx
                    (update ::changed-paths (fnil (partial reduce conj) empty-queue)
                                            (build-change-paths path))
                    (update ::db assoc-in path new)
                    (update ::changes conj [path new]))
                ctx))
            ctx
            (map vector outputs old-outputs new-outputs)))))
    ctx
    edges))

(defn input? [edge]
  (= :input (:relationship edge)))

(defn origin-path [graph origin]
  (loop [origin (vec origin)]
    (or (when (empty? origin) ::does-not-exist)
        (when (contains? graph origin) origin)
        (recur (subvec origin 0 (dec (count origin)))))))

(defn eval-traversed-edges
  "Given an origin and graph, update context with edges.

  When an node has been visited (as an input), it cannot be considered for an output"
  ([{::keys [changed-paths] :as ctx} graph]
   (let [x  (peek changed-paths)
         xs (pop changed-paths)]
     (eval-traversed-edges (assoc ctx ::changed-paths xs) graph x)))
  ([{::keys [changes] :as ctx} graph origin]
   (let [focal-origin   (origin-path graph origin)
         edges          (filter input? (get graph focal-origin #{}))
         removed-origin (dissoc graph focal-origin)
         {::keys [changed-paths] :as new-ctx} (ctx-updater edges ctx)
         x              (peek changed-paths)
         xs             (pop changed-paths)]
     (if x
       (recur (assoc new-ctx ::changed-paths xs) removed-origin x)
       new-ctx))))

(defn execute-events [{:domino.core/keys [db graph] :as ctx} inputs]
  (println inputs)
  (let [{::keys [db changes]} (eval-traversed-edges
                                (reduce
                                  (fn [ctx [path value]]
                                    (-> ctx
                                        (update ::db assoc-in path value)
                                        (update ::changed-paths conj path)
                                        (update ::changes conj [path value])))
                                  (assoc ctx ::db db
                                             ::changed-paths empty-queue
                                             ::executed-events #{}
                                             ::changes [])
                                  inputs)
                                graph)]
    (assoc ctx :domino.core/db db
               :change-history changes)))
