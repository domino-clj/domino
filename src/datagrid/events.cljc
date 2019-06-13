(ns datagrid.events
  (:require
    [clojure.walk :refer [prewalk]]))

(defn on-model-update
  "Takes the context and changes, where changes is a map of the path-value pairs.
  The path is a collection of the nested path to the value in the model."
  [ctx changes]

  )