(ns datagrid.runner
  (:require
    [doo.runner :refer-macros [doo-tests]]
    [datagrid.test-core]))

(doo-tests 'datagrid.test-core)
