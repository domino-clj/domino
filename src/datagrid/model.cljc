(ns datagrid.model
  (:require [datagrid.reactive-atom :as ratom]))

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

(def test-id-paths
  {:user    {:path [:user]}
   :fname   [:user :first-name]
   :lname   [:user :last-name]
   :address [:user :profile :address]
   :city    [:user :profile :address :city]})

(defn reactive-paths
  ([id-paths] (reactive-paths id-paths {}))
  ([id-paths state] (reactive-paths id-paths state (ratom/atom {})))
  ([id-paths state model]
    ;; TODO: probably need a more specific check to see it matches datagrid.reactive-atom's reactive needs
   (assert (satisfies? IDeref model) "Model must be derefable")

   (reset! model state)
   (reduce
     (fn [out [id path]]
       (assoc out id (ratom/cursor model path)))
     {}
     id-paths)))