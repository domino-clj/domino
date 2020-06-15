(ns user
  (:require
   [domino.core :as core :refer [transact initialize trigger-effects select]]
   [domino.examples :refer :all]
   [figwheel-sidecar.repl-api :as ra]
   [clojure.tools.namespace.repl :as repl]))

(def refresh repl/refresh)

(defn start-fw []
 (ra/start-figwheel!))

(defn stop-fw []
 (ra/stop-figwheel!))

(defn cljs []
 (ra/cljs-repl))
