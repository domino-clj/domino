(ns domino.visualize.mermaid
    (:require
      [clojure.string :as string]))

(defn- clean-arg [s]
  (-> s str
      (string/replace "-" "_")
      (string/replace ":" "")))

(defn- node [{:keys [id handler inputs outputs]}]
  (let [label (clean-arg (or id handler))]
    (concat
     (for [input inputs]
       (str (clean-arg input) " --> " label))
     (for [output outputs]
       (str label " --> " (clean-arg output))))))

(defn- nodes [{:keys [events effects]}]
  (mapcat node (concat events effects)))

(defn state-diagram [schema]
  (string/join "\n"
               (cons "stateDiagram-v2" (nodes schema))))
