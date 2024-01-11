(ns domino.visualize.mermaid
    (:require
      [clojure.string :as string]))

(defn- clean-arg [s]
  (-> s str
      (.replace "-" "_")
      (.replace ":" "")))

(defn format-md [s & args]
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
               (conj (nodes schema) "stateDiagram-v2")))
