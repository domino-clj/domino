(ns datagrid.effects)

(defn on-model-update
  "Run side effects given the changes map.
  The path is a collection of the nested path to the value in the model."
  [{:datagrid.core/keys [effects] :as ctx} changes]
  (doseq [[path value] changes]
    ))

;; Maybe some sort of batching thing here