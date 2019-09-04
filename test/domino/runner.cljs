(ns domino.runner
  (:require
    [doo.runner :refer-macros [doo-tests]]
    [domino.test-core]))

(doo-tests 'domino.test-core)
