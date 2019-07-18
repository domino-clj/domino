(ns datagrid.graph
  (:require
    [clojure.set]
    [datagrid.model :as model]))

(def conj-set (fnil conj #{}))

(def into-set (fnil into #{}))

(defn get-all-nodes [events]
  (reduce
    (fn [nodes {:keys [inputs outputs]}]
      (into nodes (concat inputs outputs)))
    #{}
    events))

(defn find-related
  "finds other nodes related by eventset"
  [input-node events]
  (->> events
       (keep (fn [{:keys [inputs outputs]}]
               (when (some #{input-node} outputs)
                 (remove #(= input-node %) inputs))))
       (apply concat)))

(defn get-triggered-events
  [input-node events]
  (-> events
      (->>
        (keep (fn [{:keys [inputs outputs] :as event}]
                (when (some #{input-node} outputs)
                  [event inputs])))
        (reduce
          (fn [related [event inputs]]
            (-> related
                (update :events conj event)
                (update :inputs into inputs)))
          {:events []
           :inputs #{}}))
      (update :inputs disj input-node)))

(defn generate-map
  ([valfn coll]
   (generate-map identity valfn coll))
  ([keyfn valfn coll]
   (into {}
         (map
           (juxt keyfn valfn)
           coll))))

(defn events-by-node [events]
  (generate-map #(get-triggered-events % events) (get-all-nodes events)))

(defn run-events [doc-old changes event-map]
  (reduce
    (fn [acc [node value]]
      (println node value (assoc acc node value))
      (let [{:keys [events]} (event-map node)]
        (reduce
          (fn [doc-evs {i :inputs o :outputs h :handler}]
            (println doc-evs)
            (->> (h nil (map (partial get doc-evs) i) (map (partial get doc-evs) o))
                 (zipmap o)
                 (merge doc-evs)))
          (assoc acc node value)
          events)))
    doc-old
    changes))

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

(defn connect
  "Generates a graph (i.e. input-kw->node-list) from a vector of nodes
  (i.e. {:inputs [...] :outputs [...] :handler (fn [ctx inputs outputs])}) "
  ([nodes]
   (connect (base-graph-ctx nodes)
            (input-nodes nodes)))
  ([ctx inputs]
   (let [[{:keys [visited graph] :as ctx} inputs] (add-nodes ctx inputs)]
     (if (not-empty inputs)
       (recur ctx (remove #(some #{%} visited) inputs))
       graph))))

(defn gen-graph
  [events]
  (reduce
    (fn [g {i :inputs o :outputs}]
      (merge-with
        clojure.set/union
        g
        (zipmap i (repeat (set o)))))
    {}
    events))

(defn reverse-edge-direction
  [graph]
  (reduce-kv
    (fn [inverted i o]
      (merge-with
        clojure.set/union
        inverted
        (zipmap o (repeat #{i}))))
    {}
    graph))

(defn gen-ev-graph
  [events]
  (reduce
    (fn [g {i :inputs o :outputs h :handler :as ev}]
      (merge-with
        clojure.set/union
        g
        (zipmap i (repeat #{{:edge ev :relationship :input :connections (set o)}}))
        (zipmap o (repeat #{{:edge ev :relationship :output :connections (set i)}}))))
    {}
    events))

(defn traversed-edges [origin graph edge-filter]
  (let [edges         (filter edge-filter (get graph origin #{}))
        related-nodes (filter
                        (partial contains? graph)
                        (disj
                          (reduce
                            clojure.set/union
                            #{}
                            (map :connections edges))
                          origin))]
    (apply clojure.set/union
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
                     (apply clojure.set/union))))
       (into {})))

(defn subgraphs [graph]
  (->> (connected-nodes-map graph (constantly true))
       vals
       distinct
       (remove empty?)
       (map #(select-keys graph %))))


;;;
(defn input? [edge]
  (= :input (:relationship edge)))

(defn get-db-paths [db paths]
  (map #(get-in db %) paths))

(def empty-queue
  #?(:clj  clojure.lang.PersistentQueue/EMPTY
     :cljs cljs.core.PersistentQueue/EMPTY))

#?(:clj
   (defmethod clojure.core/print-method clojure.lang.PersistentQueue
     [queue writer]
     (.write writer
             (str "#<PersistentQueue: " (pr-str (vec queue)) ">"))))

#_(defn changed-output-paths [output-paths old-outputs new-outputs]
    (sequence
      (comp (map vector)
            (keep (fn [[path old new]] (when (not= old new) path))))
      output-paths old-outputs new-outputs))

(defn ctx-updater [edges {::keys [db] :as ctx}]
  (reduce
    (fn [ctx {{:keys [inputs outputs handler]} :edge}]

      (let [old-outputs (get-db-paths db outputs)
            new-outputs (handler ctx
                                 (get-db-paths db inputs)
                                 old-outputs)]
        (reduce
          (fn [ctx [path old new]]
            (if (not= old new)
              (-> ctx
                  (update ::changed-paths (fnil conj empty-queue) path)
                  (update ::db assoc-in path new))
              ctx))
          ctx
          (map vector outputs old-outputs new-outputs))))
    ctx
    edges))

(defn eval-traversed-edges
  [ctx origin graph]
  (let [edges          (filter input? (get graph origin #{}))
        removed-origin (dissoc graph origin)
        {::keys [changed-paths] :as new-ctx} (ctx-updater edges ctx)
        x              (peek changed-paths)
        xs             (pop changed-paths)]                 ;; consider making multi-arity eval-traversed-edges with xs
    (if x
      (recur (assoc new-ctx ::changed-paths xs) x removed-origin)
      new-ctx)))

(defn execute-event [ctx db {{:keys [inputs outputs handler]} :edge}]
  (let [results (vec
                  (handler
                    ctx
                    (mapv #(get-in db %) inputs)
                    (mapv #(get-in db %) outputs)))]
    (when-not (= (count outputs) (count results))
      (throw
        (ex-info "number of outputs returned by the handler must match the number of declared outputs"
                 {:outputs outputs
                  :results results})))
    (reduce
      (fn [db idx]
        (assoc-in db (nth outputs idx) (nth results idx)))
      db
      (range (count outputs)))))

(defn execute-events [ctx db events [path value]]
  (reduce
    (fn [db event]
      (execute-event ctx db event))
    (assoc-in db path value)
    events))

(defn execute [ctx db graph inputs]
  (reduce
    (fn [db [path :as input]]
      (let [events (traversed-edges path graph input?)]
        (execute-events (assoc ctx ::db db) db events input)))
    db
    inputs))

(comment

  (execute-event
    {}
    {:foo {:bar 1}}
    {:edge
     {:inputs  [[:foo :bar]]
      :outputs [[:baz]]
      :handler (fn [ctx [bar] [baz]] [(inc bar)])}})

  (execute-events
    {}
    {:foo {:bar 1}}
    [{:edge
      {:inputs  [[:foo :bar]]
       :outputs [[:baz]]
       :handler (fn [ctx [bar] [baz]] [(inc bar)])}}]
    [[:foo :bar] 5])

  (def events
    [{:inputs  [[:a]]
      :outputs [[:b] [:c]]
      :handler (fn [ctx [a] [b c]] [(inc b) c])}
     {:inputs  [[:b]]
      :outputs [[:a]]
      :handler (fn [ctx [b] [a]] [(inc a)])}
     {:inputs  [[:b]]
      :outputs [[:d]]
      :handler (fn [ctx [b] [a]] [(inc a)])}
     {:inputs  [[:c]]
      :outputs [[:d]]
      :handler (fn [ctx [b] [a]] [(inc a)])}
     {:inputs  [[:d] [:a] [:g]]
      :outputs [[:e]]
      :handler (fn [ctx [d a g] [e]] [(+ d a g)])}])

  (connect events)

  (get (gen-ev-graph events) [:a])

  (execute
    {}
    {:a 0 :b 0 :c 0 :d 0 :e 0 :f 0 :g 0}
    (gen-ev-graph events)
    [[[:a] 1]])


  (eval-traversed-edges {::db {:a 0 :b 0 :c 0 :d 0 :e 0 :f 0 :g 0}} [] (gen-ev-graph events))
  ;=> #:datagrid.graph{:db {:a 0, :b 0, :c 0, :d 0, :e 0, :f 0, :g 0}}
  (eval-traversed-edges {::db {:a 0 :b 0 :c 0 :d 0 :e 0 :f 0 :g 0}} [:a] (gen-ev-graph events))
  ;=> #:datagrid.graph{:db {:a 1, :b 1, :c 0, :d 1, :e 2, :f 0, :g 0}, :changed-paths #<PersistentQueue: []>}
  )
