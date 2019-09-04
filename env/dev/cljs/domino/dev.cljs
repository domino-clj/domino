(ns ^:figwheel-no-load domino.dev
  (:require
    [domino.test-page :as test-page]
    [devtools.core :as devtools]))

(devtools/install!)

(enable-console-print!)

(test-page/init!)
