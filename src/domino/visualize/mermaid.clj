(ns domino.visualize.mermaid
    (:require
      [clojure.string :as string]))

(defn- clean-arg [s]
  (-> s str
      (string/replace "-" "_")
      (string/replace ":" "")))

(defn- format-md [s & args]
  (apply format s (map clean-arg args)))

(defn- node [{:keys [id handler inputs outputs]}]
  (concat
   (for [input inputs]
     (format-md "%s --> %s" input (or id handler)))
   (for [output outputs]
     (format-md "%s --> %s" (or id handler) output))))

(defn- nodes [{:keys [events effects]}]
  (mapcat node (concat events effects)))

(defn state-diagram [schema]
  (string/join "\n"
               (cons "stateDiagram-v2" (nodes schema))))
