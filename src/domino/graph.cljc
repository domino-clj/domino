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


;;;
(defn get-db-paths [model db paths]
  (reduce
    (fn [id->value path]
      (assoc id->value (model/id-for-path model path) (get-in db path)))
    {}
    paths))

(def empty-queue
  #?(:clj  clojure.lang.PersistentQueue/EMPTY
     :cljs cljs.core/PersistentQueue.EMPTY))

#?(:clj
   (defmethod clojure.core/print-method clojure.lang.PersistentQueue
     [queue writer]
     (.write writer (str "#<PersistentQueue: " (pr-str (vec queue)) ">"))))

(defn try-event [{:keys [pre post handler inputs] :as event} {:domino.core/keys [model] :as ctx} db old-outputs]
  (try
    (let [db-inputs (get-db-paths model db inputs)]
      (or
        (cond
          (and pre post) (post (pre handler ctx db-inputs old-outputs))
          pre (pre handler ctx db-inputs old-outputs)
          post (post (handler ctx db-inputs old-outputs))
          :else (handler ctx db-inputs old-outputs))
        old-outputs))
    (catch #?(:clj Exception :cljs js/Error) e
      (throw (ex-info "failed to execute event" {:event event :context ctx :db db} e)))))

(defn ctx-updater
  "Reducer that updates context with new values updated in ctx from
  handler of each edge. New values are only stored when they are different
  from old values.

  In changed cases, the following keys are updated:
  ::changed-paths => queue of affected paths
  ::db => temporary relevant db within context
  ::change-history => sequential history of changes. List of tuples of path-value pairs"
  [edges {::keys [db executed-events] :domino.core/keys [model] :as ctx}]
  (reduce
    (fn [ctx {{:keys [outputs] :as event} :edge}]
      (if (contains? executed-events event)
        ctx
        (let [ctx         (update ctx ::executed-events conj event)
              old-outputs (get-db-paths (:domino.core/model ctx) db outputs)
              new-outputs (try-event event ctx db old-outputs)]
          (reduce-kv
            (fn [ctx id new-value]
              ;;todo validate that the id matches an ide declared in outputs
              (if (not= (get old-outputs id) new-value)
                (let [path (get-in model [:id->path id])]
                  (-> ctx
                      (update ::changed-paths (fnil (partial reduce conj) empty-queue)
                              (generate-sub-paths path))
                      (update ::db assoc-in path new-value)
                      (update ::changes conj [path new-value])))
                ctx))
            ctx
            new-outputs))))
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
