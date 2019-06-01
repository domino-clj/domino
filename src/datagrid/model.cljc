(ns datagrid.model)

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
