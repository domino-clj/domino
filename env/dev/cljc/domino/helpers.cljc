(ns domino.helpers
  (:require [domino.core :as core]
            [clojure.set :refer [difference]]))


(defn simple "selects only the requested keys of a domino context and its subcontexts."
  ([ctx] (simple ctx [::core/db ::core/subcontexts ::core/transaction-report]))
  ([ctx ks]
   (cond-> ctx
     true
     (->
      (select-keys ks)
      (assoc :other-keys (difference
                          (set (keys ctx)) (set ks))))

     (contains? ctx ::core/subcontexts)
     (update ::core/subcontexts
             (fn process [sub]
               (reduce-kv
                #(assoc %1 %2
                        (if (::core/collection? %3)
                          (update %3 ::core/elements process)
                          (simple %3))) {} sub))))))
