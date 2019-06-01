(ns datagrid.graph
  (:require [clojure.string :as string]))

(def weight
  [{:name    :foo
    :inputs  [:foo]
    :outputs [:bar]}
   {:name    :kg->lb
    :inputs  [:kg]
    :outputs [:lb]
    :handler '(fn [_ [kg] [_]]
                [(* kg 2.2)])}
   {:name    :lb->kg
    :outputs [:kg]
    :inputs  [:lb]
    :handler '(fn [_ [lb] [_]]
                [(/ lb 2.2)])}
   {:name    :kg->bmi
    :outputs [:bmi]
    :inputs  [:kg]}])

(def events
  [{:inputs  [:a :b]
    :outputs [:c]
    :handler (fn [ctx [a b] [c]]
               [c])}
   {:inputs  [:c]
    :outputs [:d :e]
    :handler (fn [ctx [a b] [d e]]
               [d e])}
   {:inputs  [:g]
    :outputs [:h]
    :handler (fn [ctx [c g] [h]]
               [h])}
   {:inputs  [:d]
    :outputs [:f]
    :handler (fn [ctx [c d] [f]]
               [f])}])

(def events1
  [{:inputs  [:a]
    :outputs [:b]
    :handler (fn [ctx [a] [b]]
               [b])}
   {:inputs  [:b]
    :outputs [:a]
    :handler (fn [ctx [b] [a]]
               [a])}
   {:inputs  [:b]
    :outputs [:c]
    :handler (fn [ctx [b] [a]]
               [a])}])

(def events2
  [{:inputs  [:a]
    :outputs [:b :c]
    #_#_:handler (fn [ctx [a] [b]]
                   [b])}
   {:inputs  [:b]
    :outputs [:a]
    #_#_:handler (fn [ctx [b] [a]]
                   [a])}
   {:inputs  [:b]
    :outputs [:c]
    #_#_:handler (fn [ctx [b] [a]]
                   [a])}
   {:inputs  [:c]
    :outputs [:d]
    #_#_:handler (fn [ctx [b] [a]]
                   [a])}

   {:inputs  [:d :a :g]
    :outputs [:e]
    #_#_:handler (fn [ctx [b] [a]]
                   [a])}])

(def events3
  [{:inputs  [:a]
    :outputs [:a :b]}
   {:inputs  [:b]
    :outputs [:b :a]}])

(def events4
  [{:inputs  []
    :outputs []}
   {:inputs  [:a]
    :outputs [:b]}])

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

(defn tee [o]
  (println o)
  o)

(defn run-events [doc-old changes event-map]
  (reduce
    (fn [acc [node value]]
      (println node value (assoc acc node value))
      (let [{:keys [events]} (event-map node)]
        (reduce
          (fn [doc-evs {i :inputs o :outputs h :handler}]
            (println doc-evs)
            (->> (h nil (map (partial get doc-evs) i) (map (partial get doc-evs) o))
                 tee
                 (zipmap o)
                 tee
                 (merge doc-evs)
                 tee))
          (assoc acc node value)
          events)))
    doc-old
    changes))

(def model
  [[:title {:validation []}]
   [:user {:id :user}
    [:first-name {:id :fname}]
    [:last-name {:id :lname}]
    [:profile {}
     [:address {:id :address}
      [:street {}]
      [:city {:id :city}]]]]])


#_(defn paths-by-id [root]
    (tree-seq
      (fn [node]
        (and (vector? node)
             (keyword? (first node))
             (map? (second node))))
      (partial drop 2)
      root))

(defn paths-by-id
  ([root] (paths-by-id {} [] root))
  ([mapped-paths path [segment opts & children]]
   (if segment
     (let [path         (conj path segment)
           mapped-paths (if-let [id (:id opts)]
                          (assoc mapped-paths id path)
                          mapped-paths)]
       (if-not (empty? children)
         (apply merge (map (partial paths-by-id mapped-paths path) children))
         mapped-paths))
     mapped-paths)))

(defn model->paths [model]
  (apply merge (map paths-by-id model)))

(model->paths model)



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
  "Generates a graph (i.e. input-kw->node-list) from a vector of nodes (i.e. {:inputs [...] :outputs [...] :handler (fn [ctx inputs outputs])}) "
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
        (zipmap i (repeat #{(assoc ev :relationship :input :connections (set o))}))
        (zipmap o (repeat #{(assoc ev :relationship :output :connections (set i))}))))
    {}
    events))

(defn traversed-edges [origin graph get-related-nodes]
  (let [edges         (get graph origin #{})
        related-nodes (filter (partial contains? graph) (disj (reduce clojure.set/union #{} (map get-related-nodes edges)) origin))]
    (println origin related-nodes)
    (apply clojure.set/union edges (map #(traversed-edges % (dissoc graph origin) get-related-nodes) related-nodes))))

#_(defn subgraphs [graph get-related-nodes]
    (reduce
      (fn [sgs sg]
        )
      (mapv
        (fn [[n es]] (apply clojure.set/union #{n} (map get-related-nodes es)))
        graph)))

(comment

  (connect events2)

  (connect events1)

  (-> (add-nodes {:nodes events1 :graph {}} [:a]) first :visited)

  )
