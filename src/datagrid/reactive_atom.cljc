(ns datagrid.reactive-atom
  (:refer-clojure :exclude [atom])
  #?(:cljs (:require [reagent.core :as reagent])))

;; TODO: back end reactive atom implementation


;; Note: needs to be transactional, i.e. when there are changes to multiple paths in the cursors,
;; we need to execute on-model-update for them at the same time

;; Alternatively, update the atom transactionally THEN, the watchers update on-model-update accordingly
;; Implement a reactive atom. How hard could it possibly be :D

(def atom
  #?(:clj clojure.core/atom
     :cljs reagent/atom))

(defn cursor
  [src path]
  #?(:clj nil
     :cljs (cursor src path)))