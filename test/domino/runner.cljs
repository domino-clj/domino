(ns domino.runner
  (:require
    [doo.runner :refer-macros [doo-tests]]
    [domino.core-test]
    [domino.effects-test]
    [domino.events-test]
    [domino.model-test]
    [domino.subcontexts-test]
    [domino.util-test]
    [domino.validation-test]))

(doo-tests 'domino.core-test
           'domino.effects-test
           'domino.events-test
           'domino.subcontexts-test
           #_'domino.model-test
           #_'domino.util-test
           #_'domino.validation-test)
