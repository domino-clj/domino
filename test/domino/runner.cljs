(ns domino.runner
  (:require
    [doo.runner :refer-macros [doo-all-tests]]
    [domino.core-test]))

(doo-all-tests)
