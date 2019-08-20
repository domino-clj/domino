(ns datagrid.effects
  (:require
    [datagrid.effects :as effets]
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))

#_(execute-effects!
    {:changes  {[:a] 1 [:b] 1}
     ::effects (effects/effects-by-paths [{:inputs [[:a]] :handler (fn [ctx inputs]
                                                                     (prn inputs))}])})
