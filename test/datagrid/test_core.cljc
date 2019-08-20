(ns datagrid.test-core
  (:require
    [datagrid.core :refer :all]
    [datagrid.effects :refer :all]
    [datagrid.graph :refer :all]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

