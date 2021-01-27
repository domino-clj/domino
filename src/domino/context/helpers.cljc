(ns domino.context.helpers)

(defn compute-path
  "Given an ID, computes the path to the data"
  [ctx id]
  ;; TODO: test with subcontexts OR document lack of support for subcontexts.
  ((:domino.core/id->path ctx) id))

(defn field-opts
  "Gets the field opts for a given ID"
  [ctx id]
  ((:domino.core/id->opts ctx) id))

(def elide-opt-keys
  "Field options which are expensive or difficult to serialize.
   To be elided by `trimmed-opts`"
  [:schema :initialize])

(defn trimmed-opts
  "Gets a trimmed opts map for the field corresponding to the passed ID"
  [ctx id]
  (let [opts (field-opts ctx id)]
    (reduce
     (fn [acc opt]
       (if (contains? acc opt)
         (assoc acc opt :domino.core/elided)
         acc))
     opts
     (concat
      elide-opt-keys
      (:domino.core/elide-opts ctx)))))

(defn is-final?
  "Checks if a field is marked as final"
  [ctx id]
  (let [opts (field-opts ctx id)]
    (or (:ignore-updates? opts)
        (:final? opts))))

(defn ignore-final?
  "Check if the specified field (or all fields) should silently ignore changes to final fields instead of throwing an error."
  [{:domino.core/keys [ignore-finals? id->opts] :as ctx} id]
  (or (true? ignore-finals?)
      (contains? ignore-finals? id)
      (:ignore-updates? (id->opts id))))


(defn is-collection? [ctx]
  (:domino.core/collection? ctx))
