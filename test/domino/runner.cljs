(ns domino.runner
  (:require
    [doo.runner :refer-macros [doo-tests]]
    [domino.core-test]))

(doo-tests 'domino.core-test)
