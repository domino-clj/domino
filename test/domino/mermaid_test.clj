(ns domino.mermaid-test
  (:require
    [domino.visualize.mermaid :as mermaid]
    [clojure.string :as string]
    [clojure.test :refer :all]))

(deftest state-diagram-starts-with-header
  (let [schema {:events  [{:id :e1 :inputs [:a] :outputs [:b]}]
                :effects [{:id :f1 :inputs [:b]}]}
        result (mermaid/state-diagram schema)]
    (is (string/starts-with? result "stateDiagram-v2"))))
