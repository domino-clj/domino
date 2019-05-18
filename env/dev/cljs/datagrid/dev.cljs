(ns ^:figwheel-no-load datagrid.dev
  (:require
    [datagrid.test-page :as test-page]
    [devtools.core :as devtools]))

(devtools/install!)

(enable-console-print!)

(test-page/init!)
