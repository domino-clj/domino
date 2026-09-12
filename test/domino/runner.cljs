(ns domino.runner
  (:require
    [doo.runner :refer-macros [doo-tests]]
    [domino.async-test]
    [domino.core-test]
    [domino.effects-test]
    [domino.events-test]
    [domino.graph-test]
    [domino.mermaid-test]
    [domino.model-test]
    [domino.rx-test]
    [domino.util-test]
    [domino.validation-test]))

(doo-tests 'domino.async-test
           'domino.core-test
           'domino.effects-test
           'domino.events-test
           'domino.graph-test
           'domino.mermaid-test
           'domino.model-test
           'domino.rx-test
           'domino.util-test
           'domino.validation-test)
