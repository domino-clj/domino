(ns domino.visualize.graphviz
    (:require
      [rhizome.viz :as viz]))

(defn zip-keys [keys v]
  (zipmap keys (repeat v)))

(defn- model-ids [model]
  (keep :id (flatten model)))
(defn- id-or-fn [{:keys [id handler]}]
  (or id (str handler)))
(defn- make-node [type node]
  {:id (id-or-fn node) :type type})

(defn all-nodes [{:keys [model events effects]}]
  (concat
   (for [id (model-ids model)]
     {:id id :type :model})
   (for [event events]
     (make-node :event event))
   (for [effect effects]
     (make-node :effect effect))))

(defn- node-inputs [type node]
  (zip-keys
   (for [id (:inputs node)] {:id id :type :model})
   [(make-node type node)]))
(defn- nodes-inputs [type nodes]
  (->> nodes
       (map #(node-inputs type %))
       (apply merge-with concat)))

(defn- nodes-outputs [type nodes]
  (into {}
        (for [node nodes]
          [(make-node type node)
           (for [id (:outputs node)]
             {:id id :type :model})])))

(defn adjacency [{:keys [events effects]}]
  (merge
   (merge-with concat (nodes-inputs :event events) (nodes-inputs :effect effects))
   (nodes-outputs :event events)
   (nodes-outputs :effect effects)))

(defn node->descriptor [{:keys [id type]}]
  {:label id
   :fillcolor (case type :event "aliceblue" :effect "cornsilk" "white")
   :style "filled"})

(defn view-schema [schema]
  (viz/view-graph (all-nodes schema) (adjacency schema)
                  :node->descriptor node->descriptor))
