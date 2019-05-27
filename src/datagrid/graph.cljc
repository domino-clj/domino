(ns datagrid.graph)

(def rules
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

(def rules1
  [{:inputs  [:a]
    :outputs [:b]
    #_#_:handler (fn [ctx [a] [b]]
                   [b])}
   {:inputs  [:b]
    :outputs [:a]
    #_#_:handler (fn [ctx [b] [a]]
                   [a])}
   {:inputs  [:b]
    :outputs [:c]
    #_#_:handler (fn [ctx [b] [a]]
                   [a])}])

(def rules2
  [{:inputs  [:a]
    :outputs [:b]
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

   {:inputs  [:d]
    :outputs [:e]
    #_#_:handler (fn [ctx [b] [a]]
                   [a])}])

(defn find-related [input nodes]
  (->> nodes
       (keep (fn [{:keys [inputs outputs]}] (when (some #{input} outputs) inputs)))
       (apply concat)))

(defn add-nodes [ctx inputs]
  (reduce
    (fn [[{:keys [nodes] :as ctx} inputs] input]
      (let [related (find-related input nodes)]
        [(-> ctx
             (update :visited (fnil conj #{}) input)
             (update :graph update input (fnil into #{}) related))
         (into inputs related)]))
    [ctx #{}]
    inputs))

(defn connect
  ([nodes] (connect {:nodes nodes :graph {} :steps 0} (distinct (mapcat :inputs nodes))))
  ([ctx inputs]
   (let [[ctx inputs] (add-nodes ctx inputs)]
     (if (and (not-empty inputs) (< (:steps ctx) 5))
       (do
         (println (:graph ctx) inputs)
         (recur (update ctx :steps inc) (remove #(some #{%} (:visited ctx)) inputs)))
       (:graph ctx)))))

(comment

  (connect rules2)

  (find-related :a rules1)

  (-> (add-nodes {:nodes rules1 :graph {}} [:a]) first :visited)


  )
