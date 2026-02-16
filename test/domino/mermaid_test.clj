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

(deftest state-diagram-renders-edges
  (let [schema {:events  [{:id :e1 :inputs [:a] :outputs [:b]}]
                :effects [{:id :f1 :inputs [:b]}]}
        result (mermaid/state-diagram schema)]
    (is (string/includes? result "a --> e1"))
    (is (string/includes? result "e1 --> b"))
    (is (string/includes? result "b --> f1"))))

(deftest clean-arg-handles-special-chars
  (let [schema {:events [{:id :my-event :inputs [:my-input] :outputs [:my-output]}]}
        result (mermaid/state-diagram schema)]
    (is (string/includes? result "my_input"))
    (is (string/includes? result "my_event"))
    (is (not (string/includes? result ":")))))
