(ns domino.rx-test
  (:require
   [domino.rx :as rx]
   #?(:clj  [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [is are deftest testing use-fixtures]])))



;; Create Reactive Map
;; This is the `initialization` of an empty rx map. Not really worth testing in isolation.
;; A data structure that supports the following operations:
;;  - add-reaction
;;  - get-reaction
;;  - update-value (for which update-root is a special case)


;; Compute Reaction (implementation detail)
;; This returns an updated version of the reactive-map it is called on
;;   with the specified reaction recomputed if neccessary.
;;   a means for caching get-reaction, which is the primary API function.

;; Update Value (update-root)
;; This is the primary means of updating "mutable" values in a reactive map.
;; These updates are the point from which all reactions derive their value.

;; Get Reaction
;; Gets the current value of the specified reaction from the reactive-map
;; Runs `compute-reaction` if required beforehand.

;; Add Reaction
;; A means of registering new reactions on the reactive-map.
;; TODO: document reaction API/DSL Syntax.

;; args->inputs (trivial)
