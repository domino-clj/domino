(ns domino.core
  (:require
   [domino.effects :as effects]
   [domino.events :as events]
   [domino.graph :as graph]
   [domino.model :as model]
   [domino.validation :as validation]
   [domino.util :as util]
   [domino.rx :as rx]))


;; ==============================================================================
;; Collection Notes

;; 1. How should a collection look in the model specification?
;; Consider the following options for data which looks like this:
#_{:patients [{:mrn "0003456"
               :name {:given "Bugs"
                      :family "Bunny"}
               :medications [{:rx-id "110120011"
                              :creation-date #inst "2020-05-10T19:44:32.743Z"
                              :name "Aspirin"
                              :dose "500"
                              :unit "mg"}
                             {:rx-id "889012299"
                              :creation-date #inst "2020-05-11T18:22:30.000Z"
                              :name "Tylenol"
                              :dose "200"
                              :unit "mg"}
                             {:rx-id "asdfasdfasdf"
                              :name "New Med"}]}
              {:mrn "1234123"
               :name {:given "Donald"
                      :family "Duck"}
               :medications [{:rx-id "001044304"
                              :creation-date #inst "2020-04-27T18:06:00.000Z"
                              :name "Advil"
                              :dose "200"
                              :unit "mg"}
                             {:rx-id "908011238"
                              :creation-date #inst "2020-04-29T18:06:00.000Z"
                              :name "Advil"
                              :dose "100"
                              :unit "mg"}
                             {:rx-id "440300210"
                              :creation-date #inst "2020-05-04T14:09:00.000Z"
                              :name "Tylenol"
                              :dose "500"
                              :unit "mg"}]}]
   :shift {:start #inst "2020-05-12T08:00:00.000Z"
           :end #inst "2020-05-12T16:00:00.000Z"}}

#_{:id :given-name :params {:patients {:mrn "1234123"}}}
#_[:patients {:mrn "1234123"} :name :first]

#_{:inputs [[:patients [:asdfasdf]]]}

;; Option 1: the map for the collection itself specifies the collection behaviour. e.g.
#_[[:patients {:index-ks [:mrn]
               :order-by [:surname :given-name :mrn]}
    [:mrn {:id :mrn}]
    [:risk-level {:id :risk}]
    [:name
     [:first {:id :given-name}]
     [:last {:id :surname}]]
    [:medications {:index-ks [:rx-id]
                   :order-by [:creation-date :rx-id]}
     [:rx-id {:id :rx-id}]
     [:creation-date {:id :creation-date}]
     [:name {:id :drug}]
     [:dose {:id :dose}]
     [:unit {:id :unit}]]]
   [:shift
    [:start {:id :start-date}]
    [:end {:id :end-date}]]]

;;   Notes
;;   - placed like a type annotation or an ID, similar pattern to working with primitives
;;   - Could be aggregated from the opts map, but it's hard to know what is needed at a glance

;; Option 2: there is a child segment that navigates to matching children
;;           (NOTE: this may allow multiple overlapping matchers, should consider when and when not to share things (e.g. only apply some validation based on segment matcher?))
;; i.e. in `path-params` there is a `:patient`, which is of the form: `{:mrn <patient's MRN>}`. the use of the top-level `:patient` key in params is to prevent conflicts. In theory could allow `{:mrn :mrn}` directly, and use elsewhere.
#_[[:patients {:order-by [:surname :given-name :mrn]}
    [{:patient {:some-random-name :mrn}}
     ;; could also add opts by matcher here?
     [:mrn {:id :mrn}]
     [:name
      [:first {:id :given-name}]
      [:last {:id :surname}]]
     [:medications {:order-by [:creation-date :rx-id]}
      [{:medication {:rx-id :rx-id}}
       [:rx-id {:id :rx-id}]
       [:creation-date {:id :creation-date}]
       [:name {:id :drug}]
       [:dose {:id :dose}]
       [:unit {:id :unit}]]]]]
   [:shift
    [:start {:id :start-date}]
    [:end {:id :end-date}]]]

;;   Notes
;;   - Establishes a pattern of dynamic path segments
;;   - May require unneccessary duplication/coordination of field IDs
;;   - possibility of multiple children with dynamic segments is double-edged
;;     - Allows for greater flexibility in application
;;     - Can be tricky and hard to reason about
;;     - Unclear what 'canonical' indexing is

#_{:patients {{:mrn "1234123"
               :visit "123123"} {:mrn "1234123"
                                 :name {:first "Donald"
                                        :last "Duck"}}}}

;; Option 3: the map for the collection itself specifies an entire domino context.
;; i.e. each time we navigate to a domino context for editing or for rules/reactions, we defer to the inner one first before resolving the outer context
#_[[:patients
    {:id :patients
     :element-id :patient
     :index-ks [:mrn]
     :order-by [:surname :given-name :mrn]
     :entry-schema
     {:model
      [[:mrn {:id :mrn}]
       [:name
        [:first {:id :given-name}]
        [:last {:id :surname}]]
       [:risk]
       [:medications
        {:id :medications
         :element-id :medication
         :index-ks [:rx-id]
         :order-by [:creation-date :rx-id]
         :entry-schema
         {:model
          [[:rx-id {:id :rx-id}]
           [:creation-date {:id :creation-date}]
           [:name {:id :drug}]
           [:dose {:id :dose}]
           [:unit {:id :unit}]]}}]]
      :events [{:inputs [:given-name :surname]
                :outputs [:dfdf]}]}}]
   [:shift
    [:start {:id :start-date}]
    [:end {:id :end-date}]]]

#_[:patient :medications]

#_:patient

#_[:patient :medication :dose] ;;=> [:patients "1231231" :medications "1234"]

#_(get-in data [:patients 0 :medications])
#_(get-in data [:patients 0 :medications 0])

#_(fn [medications]
    (when (< 5 (count medications))
      {:risk "High"}))

;;   Notes
;;   - By completely nesting we get extremely explicit
;;   - Easy enough to do collections of primitives in the same manner
;;   - May cause annoying friction or opacity
;;   - Allows for rules to be placed inside nested context
;;   - Solves reference of ids from rules (at least for context-local rules)


;; To Resolve:
;; - How to manage triggering of reactions across collection member boundary?
;;   - (outside triggering inside and vice versa)
;; - Ensure minimal recomputation (e.g. re-indexing etc.)
;; - What about rules which operate on aggregates of predicates? (e.g. at least one emergency contact must have a phone number provided)
;; - What about rules on polymorphic collections? What about polymorphic collections in general?
;; - How to convert from collection to map on index and vice versa?
;;   - How to preserve order when neccessary without jumbling newly added elements?
;; - How to enforce things like unique fields or foreign-key-like references?


;; Conversation notes
;; 1. Persistence Shape
;; - Do we want to convert collections to maps (where the keys are the unique index)?
;;   - If so, do we want to provide a view for egress which converts these back into collections?
;;     - Should this be explicit or inferred?
;;   Conclusion: Should be maps when persisted, collection view can be dealt with later...
;;
;; 2. Relationships/events/rules across collection boundary
;; - How do we want to express rules like this in data?
;;   - (e.g. apply to all members which pass predicate x)
;;   - (e.g. Collection must have at least one/exactly one which pass x)
;; - Ergonomic and requires as little knowledge of the particulars as possible

;; 3.


;; Decisions
;; 1. Rules/Events *ALWAYS* use ids, never path literals.
;; 2. if a id/reference is a keyword it belongs to the top-level context, additionally it may be registered as dynamic, in which case it refers to each child in a collection.
;; 3. If a id/reference is a vector, the first element refers to a dynamic id in the top-level context, and subsequent ids refer to subsequently nested contexts.



;; Relationship model: Compute each nested context's relationship graph first, then use them to compute top-level relationships.
;;                     Don't support arbitrary predicates, just keep a one-to-one relationship from input to output.

;; Evaluation model: Each update to a child is a nested call to transact. Resolve inner contexts as if they were an in-place assoc-in.



;; END Collection Notes
;; ==============================================================================

(defn dissoc-in [m [k & ks]]
  (if-some [submap (and ks
                        (some-> m
                                (get k)
                                (dissoc-in ks)
                                (not-empty)))]
    (assoc m k submap)
    (dissoc m k)))

(defn compute-path [ctx id]
  ((::id->path ctx) id))

(defn is-collection? [ctx]
  (::collection? ctx))

(defn create-new-context-in-collection [ctx idx]
  ((::create-element ctx) idx))

(declare transact)

(defn resolve-change
  "Given a domino context, applies the change to the current DB state.
  Doesn't apply rules yet."
  [ctx change]
  (case (first change)
    ::set-value
    (let [[_ id val] change]
      (update ctx ::db assoc-in (compute-path ctx id) val))
    ::remove-value
    (update ctx ::db dissoc-in (compute-path ctx (second change)))

    ::update-child
    ;;TODO: Aggregate transaction reports somehow
    ;;TODO: unwrap long child-ctx paths in parse change
    (let [[_ [id idx?] & changes] change
          sub-context ((::subcontexts ctx) id)
          collection? (is-collection? sub-context)
          ;;TODO: Instead of duplicating context on creation, consider doing a merge.
          child-ctx (if collection?
                      (or (get-in sub-context [::elements idx?])
                          (create-new-context-in-collection sub-context idx?))
                      sub-context)
          {child-v ::db
           tx ::transaction-report
           :as new-child-ctx} (transact child-ctx
                                        changes)
          subctx-id (if collection? [id idx?] [id])]
      (if (= :failed (:status tx))
        (throw (ex-info (:message (:data tx)) (update (:data tx) :subcontext-id (partial into [(if collection? [id idx?] [id])]))))
        (-> ctx
            (assoc-in (if collection?
                        [::subcontexts id ::elements idx?]
                        [::subcontexts id])
                      new-child-ctx)
            (update ::db assoc-in (cond-> (compute-path ctx id)
                                    collection? (conj idx?))
                    child-v)
            (update ::child-tx-reports
                    (fnil assoc {}) subctx-id tx))))

    ::remove-child
    (let [[_ [id idx]] change]
      (-> ctx
          (update-in [::subcontexts id ::elements] dissoc idx)
          (update ::db dissoc-in (conj (compute-path ctx id) idx))))

    (throw (ex-info "Invalid change!"
                    {:id ::invalid-change
                     :message "Invalid change!"
                     :change change}))))

(defn- resolve-changes-impl
  [ctx changes]
  (reduce
   resolve-change
   ctx
   changes))

(defn parse-change [ctx change]
  (case (first change)
    ::set
    (let [[_ k? m?] change]
      (if (map? k?)
        (reduce-kv
         (fn [acc k v]
           (into acc
                 (if (coll? v)
                   (if-let [{::keys [collection? index-id]} ((::subcontexts ctx) k)]
                     (if collection?
                          (reduce-kv
                           (fn [acc idx vv]
                             (into
                              acc
                              (parse-change
                               ctx
                               [::update-child [k idx] [::set vv]])))
                           []
                           (if (map? v)
                             v
                             (reduce
                              (fn [acc vv]
                                (assoc acc (get vv index-id) vv))
                              {}
                              v)))
                          (parse-change ctx
                                        [::update-child [k] [::set v]]))
                     (parse-change ctx [::set-value k v]))
                   (parse-change ctx [::set-value k v]))))
         []
         k?)
        (parse-change ctx [::update-child k? [::set m?]])))

    ::set-value
    (let [[_ id val] change]
      (cond
        (nil? val)
        (parse-change ctx [::remove-value id])

        (vector? id)
        (cond
          (contains? (::subcontexts ctx) (first id))
          (parse-change
           ctx
           (if (::collection?
                (get (::subcontexts ctx) (first id)))
             [::update-child (subvec id 0 2) [::set-value (subvec id 2) val]]
             [::update-child (subvec id 0 1) [::set-value (subvec id 1) val]]))

          (and (= 1 (count id)) (contains? (::id->path ctx) (first id)))
          (parse-change
           ctx
           [::set-value (first id) val]))

        :else
        [change]))

    ::remove-value
    (let [[_ id] change]
      (if (vector? id)
        (let [[k & [idx? :as ks]] id
              {::keys [collection?] :as sub-ctx} (get (::subcontexts ctx) k)
              sub-id (vec (if collection? (rest ks) ks))]
          (if (empty? sub-id)
            (if (or collection? (nil? sub-ctx))
              (parse-change ctx (if collection?
                                  [::remove-child [k idx?]]
                                  [::remove-value k]))
              (throw (ex-info "Cannot use `::remove-child` for entire non-collection subcontext."
                              {:change change})))
            (if (nil? sub-ctx)
              (throw (ex-info "No subcontext found for segment of vector ID!"
                              {:change change}))
              (parse-change ctx (parse-change
                                 ctx
                                 (if (::collection?
                                      (get (::subcontexts ctx) (first id)))
                                   [::update-child (subvec id 0 2) [::remove-value (subvec id 2)]]
                                   [::update-child (subvec id 0 1) [::remove-value (subvec id 1)]]))))))
        [change]))

    ::update-child
    (let [[_ [k :as id] & changes] change
          {::keys [collection?] :as sub-ctx?} (get (::subcontexts ctx) k)
          sub-id (if collection? (subvec id 2) (subvec id 1))]
      (if (empty? sub-id)
        [change]
        (parse-change ctx [::update-child
                           (if collection?
                             (subvec id 0 2)
                             (subvec id 0 1))
                           (into [::update-child sub-id] changes)])))

    ::remove-child
    (let [[_ [k :as id]] change
          {::keys [collection?] :as sub-ctx?} (get (::subcontexts ctx) k)
          sub-id (if collection? (subvec id 2) (subvec id 1))]
      (if (empty? sub-id)
        [change]
        (parse-change ctx [::update-child
                           (if collection?
                             (subvec id 0 2)
                             (subvec id 0 1))
                           [::remove-child sub-id]])))

    ;;change type doesn't have explicit parsing
    (cond
      ;; Check if a custom change fn exists, and pass each resultant change to the parser again
      (contains? (::custom-change-fns ctx) (first change))
      (reduce into [] (map (partial parse-change ctx) ((::custom-change-fns ctx) change)))

      ;; Default to set-value if the first element is a keyword with a valid path
      (and (keyword? (first change)) (compute-path ctx (first change)))
      (parse-change ctx (into (if (map? (second change))
                                [::set]
                                [::set-value]) change))

      (map? change)
      (parse-change ctx [::set change])

      (vector? (first change))
      (parse-change ctx (into (if (map? (last change))
                                [::set]
                                [::set-value])
                              change))

      :else
      [change])))

(defn group-changes [ctx changes]
  (let [processed (reduce
                   (fn [acc change]
                     (case (first change)
                       ::update-child
                       (let [[_ target & chs] change]
                         (if-some [idx (get acc target)]
                           ;; Check the map to see if the target's index is registered.
                           ;; If it is, update :changes at idx by conj'ing the changes
                           (-> acc
                               (update :changes update idx into chs))
                           ;; Otherwise, add the change as is, and record the index in case it's needed later.
                           (-> acc
                               (update :changes conj change)
                               (assoc target (count (:changes acc))))))
                       ;; If it's not `::update-child`, don't aggregate
                       (update acc :changes conj change)))
                   {:changes []}
                   changes)]
    (:changes processed)))

(defn parse-changes [ctx changes]
  ;; TODO: perform some aggregation of child changes, look for custom change types on context (inspired by transaction fns from datomic)
  (->> changes
       (reduce
        (fn [parsed change]
          (into parsed
                (parse-change ctx change)))
        [])
       (group-changes ctx)))

(defn get-child-tx-reports [ctx]
  (if-some [children (::child-tx-reports ctx)]
    (-> ctx
        (update ::transaction-report assoc ::children children)
        (dissoc ::child-tx-reports))
    ctx))

(defn resolve-changes [ctx changes]
  ;;TODO: manage change-parsing and transaction annotation from `transact` fn
  ;; consider compiling the transaction-report from ephemeral keys assoc'd here (i.e. middleware style)
  (let [parsed-changes (parse-changes ctx changes)]
    (try
      (-> ctx
          (resolve-changes-impl parsed-changes)
          (assoc ::transaction-report {:status :complete})
          (get-child-tx-reports)
          (update-in
           [::transaction-report :changes]
           (fnil into [])
           parsed-changes))
      (catch clojure.lang.ExceptionInfo ex
        (let [data (ex-data ex)]
          (case (:id data)
            ::invalid-change
            (assoc ctx ::transaction-report {:status :failed
                                             :reason ::invalid-change
                                             :data   data})
            (throw ex)))))))

(defn get-db [ctx]
  (-> ctx ::rx ::db :value))

(defn transact
  ([ctx changes]
   (transact ctx ctx changes))
  ([ctx-pre {state ::rx :as ctx} changes]
   (let [append-db-hash! (fn [ctx]
                           (let [hashes (::db-hashes ctx #{})
                                 h (hash (get-db ctx))]
                             (if (hashes h)
                               (throw (ex-info "Repeated DB state within transaction!"
                                               {:hashes hashes
                                                ::db (get-db ctx)}))
                               (update ctx ::db-hashes (fnil conj #{}) h))))
         {::keys [db] :as ctx-next} (resolve-changes ctx changes)
         {state ::rx :as ctx} (-> ctx-next
                                  (update ::rx rx/update-root assoc ::db db)
                                  append-db-hash!)]
     ;; TODO: decide if this is guaranteed to halt, and either amend it so that it is, or add a timeout.
     ;;       if a timeout is added, add some telemetry to see where cycles arise.
     (let [{::keys [unresolvable resolvers]
            :as conflicts} (rx/get-reaction state ::conflicts)]
       (cond
         (empty? conflicts)
         (do
           (-> ctx
               (dissoc ::db-hashes)
               (assoc ::db (get-db ctx))))

         (not-empty unresolvable)
         (throw (ex-info "Unresolvable constraint conflicts exist!"
                         {:unresolvable unresolvable
                          :conflicts conflicts}))

         ;; TODO: aggregate compatible changes from resolvers, or otherwise synthesize a changeset from a group of resolvers.
         (not-empty resolvers)
         (let [resolver-ids (vals resolvers)
               {state ::rx :as ctx} (update ctx ::rx #(reduce rx/compute-reaction % resolver-ids))
               changes (reduce (fn [acc resolver] (into acc (rx/get-reaction state resolver))) [] resolver-ids)]
           (recur ctx-pre ctx changes))

         :else
         (throw (ex-info "Unexpected key on :domino/conflicts. Expected :domino.core/resolvers, :domino.core/passed, and/or :domino.core/unresolvable."
                         {:unexpected-keys (remove #{::resolvers ::passed ::unresolvable} (keys conflicts))
                          :conflicts conflicts
                          :reaction-definition (get state ::conflicts)})))))))

(defn model-map-merge [opts macc m]
  ;; TODO: custom merge behaviour for privileged keys.
  ;;       Possibly also provide a multimethod to allow custom merge beh'r for user specified keys.
  (merge macc m))

(defn clean-macc [opts m]
  (select-keys m (into [] (:inherited-keys opts))))

(defn walk-model
    [raw-model {:as opts}]
    (letfn [(walk-node [parent-path macc [segment & [m? :as args]]]
              (let [[m children] (if (map? m?)
                                   [(model-map-merge opts macc m?) (rest args)]
                                   [macc args])
                    ;; TODO parse-segment should handle params etc.
                    path ((fnil conj []) parent-path segment)
                    next-macc (clean-macc opts m)]
                (cond-> []
                  (:id m)               (conj [(:id m) (assoc m ::path path)])
                  (not-empty children) (into
                                        (reduce (fn [acc child] (into acc (walk-node path next-macc child))) [] children))
                  ;; TODO: Add validation constraint parsing
                  ;; TODO: Add dispatch/expansion/compilation on path and/or m for a custom reaction collection
                  )))]
      (reduce
       (fn [acc sub-model]
         (into acc (walk-node [] {} sub-model)))
       []
       raw-model)))

(def default-reactions [{:id ::db
                           :args ::rx/root
                           :fn ::db}
                          ;; NOTE: send hash to user for each change to guarantee synchronization
                          ;;       - Also, for each view declared, create a sub for the sub-db it cares about
                          ;;       - Also, consider keeping an aggregate of transactions outside of ::db (or each view) to enable looking up an incremental changeset, rather than a total refresh
                          ;;       - This is useful, because if a hash corresponds to a valid ::db on the server, it can be used to ensure that the client is also valid without using rules.
                          ;;       - Also, we can store *just* the hash in the history, rather than the whole historical DB.
                          ;; NOTE: locks could be subscriptions that use hash and could allow for compare-and-swap style actions
                          {:id [::db :util/hash]
                           :args ::db
                           :fn hash}])

(defn passing-constraint-result? [result]
  (or (true? result) (nil? result)))

(defn constraint->reactions [constraint]
  (let [resolver (:resolver constraint)
        resolver-id [::resolver (:id constraint)]
        pred-reaction (cond->
                          {:id [::constraint (:id constraint)]
                           ::is-constraint? true
                           ::has-resolver? (boolean resolver)
                           :args-format :map
                           :args (:query constraint)
                           :fn
                           (:pred constraint)}
                        ;; TODO: handle dynamic query stuff
                        resolver (->(assoc ::resolver-id resolver-id)
                                    (update :fn (partial
                                                 comp
                                                 #(if (passing-constraint-result? %)
                                                    %
                                                    resolver-id)))))]

    (cond-> [pred-reaction]
      resolver (conj
                {:id [::resolver (:id constraint)]
                 :args-format :map
                 :lazy? true
                 :args (:query constraint)
                 :fn (comp
                      (fn [result]
                        (reduce-kv
                         (fn [changes k id]
                           (if (contains? result k)
                             (conj changes [::set-value id (get result k)])
                             changes))
                         []
                         (:return constraint)))
                      (:resolver constraint))}))))

(defn parse-reactions [schema]
  (-> default-reactions
      ;; TODO Add reactions parsed from model
      ;; TODO Add reactions parsed from other places (e.g. events/constraints)
      ;; (into (walk-model (:model schema) {})) ;; TODO: Use compiled model from new walk.
      (into (:reactions schema))
      (into (mapcat constraint->reactions (:constraints schema)))))

(defn get-constraint-ids [state]
  (keep (fn [[k v]]
          (when (::is-constraint? v)
            k))
        state))

(defn add-conflicts-rx! [state]
  (rx/add-reaction! state {:id ::conflicts
                           :args-format :rx-map
                           :args (vec (get-constraint-ids state))
                           :fn (fn [preds]
                                 ;; NOTE: Should this be made a lazy seq?
                                 (let [conflicts
                                       (reduce-kv
                                        (fn [m pred-id result]
                                          (cond
                                            ;;TODO: add other categories like not applicable or whatever comes up
                                            (or (true? result) (nil? result))
                                            (update m ::passed (fnil conj #{}) pred-id)

                                            (false? result)
                                            (update m ::unresolvable
                                                    (fnil assoc {})
                                                    pred-id
                                                    {:id pred-id
                                                     :message (str "Constraint predicate " pred-id " failed!")})

                                            (and (vector? result) (= (first result) ::resolver))
                                            (update m ::resolvers
                                                    (fnil assoc {})
                                                    pred-id
                                                    result)

                                            :else
                                            (update m ::unresolvable
                                                    (fnil assoc {})
                                                    pred-id
                                                    (if (and (map? result)
                                                             (contains? result :message)
                                                             (contains? result :id))
                                                      result
                                                      {:id pred-id
                                                       :message (str "Constraint predicate " pred-id " failed!")
                                                       :result result}))))
                                        {}
                                        preds)]
                                   (when (not-empty (dissoc conflicts ::passed))
                                     conflicts)))}))


(declare initialize)

(defn add-field-to-ctx [{::keys [db] :as ctx} [id {::keys [path] :keys [collection? schema index-id] :as m}]]
  (let [ctx'
        (cond-> ctx
          path (->
                (update ::id->path (fnil assoc {}) id path)
                (update ::reactions (fnil into []) [{:id id
                                                     :args ::db
                                                     :args-format :single
                                                     :fn #(get-in % path (:val-if-missing m))}]))
          (not-empty m) (update ::id->opts (fnil assoc {}) id m)
          schema (->
                  (update ::subcontexts (fnil assoc {}) id
                          (if collection?
                            {::collection? true
                             ::index-id index-id
                             ::create-element (fn [idx]
                                                (let [{::keys [id->path] :as el} (initialize schema)]
                                                  (update el ::db assoc-in
                                                          (id->path index-id [::index])
                                                          idx)))
                             ::elements (reduce
                                         (fn [m [k el]]
                                           (let [ctx (initialize schema el)]
                                             (assoc m k ctx)))
                                         {}
                                         (get-in db path))}
                            (initialize schema (get-in db path))))))]
    (if schema
      (update ctx' ::db assoc-in path (if collection?
                                        (into {}
                                              (map
                                               (fn [[k el]]
                                                 [k (::db el)])
                                               (get-in ctx' [::subcontexts id ::elements])))
                                        (get-in ctx' [::subcontexts id ::db])))
      ctx')))

(defn initialize
  ([schema]
   (initialize schema {}))
  ([schema initial-db]
   (let [fields (walk-model (:model schema) {})
         ctx (reduce
              add-field-to-ctx
              {::db initial-db
               ::rx (rx/create-reactive-map initial-db)
               ::schema schema}
              fields)
         ctx (-> ctx
                 (update ::rx rx/add-reactions! (into (::reactions ctx)
                                                      (parse-reactions schema)))
                 (update ::rx add-conflicts-rx!)
                 (transact []))]
     (transact ctx []))))

(def example-schema-trivial
  "
  This schema is the simplest type. It has no rules, no validation, no computation, and no collections.
  It is trivial, and is equivalent to a nested map.
  For example:
  (transact <instance> [[:mrn \"1234123\"] [:surname \"Anderson\"]])
  is equivalent to:
  (update <instance> ::db
         #(-> %
              (assoc-in [:patient :mrn] \"1234123\")
              (assoc-in [:patient :name :surname] \"Anderson\")))

  (select <instance> :mrn) NOTE: API for selecting to be determined
  is equivalent to:
  (get-in <instance> [::db :patient :mrn])"
  {:model [[:patient
            [:mrn {:id :mrn}]
            [:name
             [:first {:id :given-name}]
             [:last  {:id :surname}]
             [:full {:id :full-name}]]]]
   :constraints [{:id :compute-full-name
                  :query {:f :given-name
                          :l :surname
                          :n :full-name}
                  :return {:n :full-name}
                  :pred (fn [{:keys [f l n]}]
                          (= (str l ", " f) n))
                  :resolver (fn [{:keys [f l n]}]
                              {:n (str l ", " f)})}]})


(def example-schema-nested
    "
  This schema is an example of non-collection nesting.
  This allows you to defer to the inner schema of `:patient` for accessing its contents.
  For example:
  (transact <instance> [[[:patient :surname] \"Anderson\"] [:bed-id \"122-A\"] [[:patient :given-name] \"Mr.\"]])
  is equivalent to:
  (update <instance> ::db
          #(-> %
              (update-in [:patient] transact [[:surname \"Anderson\"]])
              (assoc-in  [:bed] \"122-A\")
              (update-in [:patient] transact [[:surname \"Mr.\"]])))

  (transact <instance [[:patient {:mrn \"1234123\" :name {:first \"Mr.\" :last \"Anderson\"}}]])

  (select <instance> [:patient :surname])
  is equivalent to:
  (select (get-in <instance> [::db :patient]) :surname)

  NOTE:
  (select <instance> :patient)
  is NOT:
  (get-in <instance> [::db :patient])
  but rather:
  (get-in <instance> [::db :patient ::db])
  "
    {:model [[:bed {:id :bed-id}]
             [:stay
              [:start {:id :start}]
              [:end   {:id :end}]]
             [:patient {:id :patient
                        :schema
                        {:model
                         [[:mrn {:id :mrn}]
                          [:name
                           [:first {:id :given-name}]
                           [:last {:id :surname}]
                           [:full {:id :full-name}]]]
                         :constraints [{:id :compute-full-name
                                        :query {:f :given-name
                                                :l :surname
                                                :n :full-name}
                                        :return {:n :full-name}
                                        :pred (fn [{:keys [f l n]}]
                                                (= (str l ", " f) n))
                                        :resolver (fn [{:keys [f l n]}]
                                                    {:n (str l ", " f)})}]}}]]})

(def example-schema-1
    ""
    {:model
     [[:patients
       {:id :patients
        :index-id :mrn
        :order-by [:surname :given-name :mrn]
        :collection? true
        :schema
        {:constraints [{:id :compute-full-name
                        :query {:f :given-name
                                :l :surname
                                :n :full-name}
                        :return {:n :full-name}
                        :pred (fn [{:keys [f l n]}]
                                (= (str l ", " f) n))
                        :resolver (fn [{:keys [f l n]}]
                                    {:n (str l ", " f)})}]
         :model
         [[:mrn {:id :mrn}]
          [:name
           [:first {:id :given-name}]
           [:last {:id :surname}]
           [:full {:id :full-name}]]
          [:risk {:id :risk}]
          [:medications
           {:id :medications
            :index-id :rx-id
            :order-by [:creation-date :rx-id]
            :collection? true
            :schema
            {:model
             [[:rx-id {:id :rx-id}]
              [:creation-date {:id :creation-date}]
              [:name {:id :drug}]
              [:dose {:id :dose}]
              [:unit {:id :unit}]]}}]]}}]
      [:shift
       [:start {:id :start-date}]
       [:end {:id :end-date}]
       [:length {:id :shift-length}]]]
     :events [{:inputs [:shift-length [:patient :medication :dose]] ;; [:patients "1234123" :medications "224-A" :dose]
               :outputs [[:patient :medication :unit]]}]})
(comment



  (def example-schema-computed-value
    "
  This schema demonstrates one of the uses of events: deriving a value from another."
    {:model [[:patient
              [:mrn {:id :mrn}]
              [:name
               [:first {:id :given-name}]
               [:last  {:id :surname}]
               [:full {:id :full-name}]]]]
     ;; NOTE: Syntax enrichment for inputs/args to functions is subject to change.
     ;;       We will use better DSL to enable restriction of inputs, and declaration of what is neccessary on the context.
     ;;       We will also make it easier to generate `events`, chain them together, and inspect dependencies and dependents.
     :events [{:inputs [:given-name :surname]
               :outputs [:full-name]
               :fn (fn [_ {:keys [given-name surname]} _]
                     {:full-name (str surname ", " given-name)})}]})

  ;; 1. Support nested changes: [[:patient [:domino/transact [:]  ]]]

  ;; 2. Collapse all subsequences into single transactions.

  ;; 3. Collapse all changes and transact each context at most once.


  {::db {:patients {"1234123" {:mrn "1234123"}}}
   ::related-paths {}}

  (def example-schema-nested
    "
  This schema is an example of non-collection nesting.
  This allows you to defer to the inner schema of `:patient` for accessing its contents.
  For example:
  (transact <instance> [[[:patient :surname] \"Anderson\"] [:bed-id \"122-A\"] [[:patient :given-name] \"Mr.\"]])
  is equivalent to:
  (update <instance> ::db
          #(-> %
              (update-in [:patient] transact [[:surname \"Anderson\"]])
              (assoc-in  [:bed] \"122-A\")
              (update-in [:patient] transact [[:surname \"Mr.\"]])))

  (transact <instance [[:patient {:mrn \"1234123\" :name {:first \"Mr.\" :last \"Anderson\"}}]])

  (select <instance> [:patient :surname])
  is equivalent to:
  (select (get-in <instance> [::db :patient]) :surname)

  NOTE:
  (select <instance> :patient)
  is NOT:
  (get-in <instance> [::db :patient])
  but rather:
  (get-in <instance> [::db :patient ::db])
  "
    {:model [[:bed {:id :bed-id}]
             [:stay
              [:start {:id :start}]
              [:end   {:id :end}]]
             [:patient {:id :patient
                        :schema
                        {:model
                         [[:mrn {:id :mrn}]
                          [:name
                           [:first {:id :given-name}]
                           [:last {:id :surname}]
                           [:full {:id :full-name}]]]
                         :events [{:inputs [:given-name :surname]
                                   :outputs [:full-name]
                                   :fn (fn [_ {:keys [given-name surname]} _]
                                         {:full-name (str surname ", " given-name)})}]}}]]})

  (def example-schema-1
    ""
    {:model
     [[:patients
       {:id :patients
        :element-id :patient
        :index-ks [:mrn]
        :order-by [:surname :given-name :mrn]
        :entry-schema
        {:model
         [[:mrn {:id :mrn}]
          [:name
           [:first {:id :given-name}]
           [:last {:id :surname}]]
          [:risk {:id :risk}]
          [:medications
           {:id :medications
            :element-id :medication
            :index-ks [:rx-id]
            :order-by [:creation-date :rx-id]
            :entry-schema
            {:model
             [[:rx-id {:id :rx-id}]
              [:creation-date {:id :creation-date}]
              [:prescription-summary {:id :rx}]
              [:name {:id :drug}]
              [:dose {:id :dose}]
              [:unit {:id :unit}]]
             :constraints
             {:id :compute-prescription-summary
              #_
              {:id       :compute-c
               :query    {:a :a
                          :b :b
                          :c :c}
               :pred     (fn [{:keys [a b c]}]
                           (= c (+ a b)))
               :resolver {:query  {:a :a :b :b}
                          :return {:c [:a+b=c :c]}
                          :fn     (fn [{:keys [a b]}]
                                    {:c (+ a b)})}}
              :query {:drug :drug
                      :dose :dose
                      :unit :unit
                      :rx   :rx}
              :pred (fn [{:keys [drug dose unit rx]}]
                      (= (str drug " " dose unit) rx))
              :resolver {:query {:drug :drug
                                 :dose :dose
                                 :unit :unit
                                 :rx   :rx}
                         }}}}]]}}]
      [:shift
       [:start {:id :start-date}]
       [:end {:id :end-date}]
       [:length {:id :shift-length}]]]
     :events [{:inputs [:shift-length [:patient :medication :dose]] ;; [:patients "1234123" :medications "224-A" :dose]
               :outputs [[:patient :medication :unit]]}
              ]})



  ;; TODO: remove example stuff
  ;; TODO: Add support for async calls in resolvers
  ;; TODO: Add ability for resolvers to compare query to previous value ()
  ;; TODO: Add key on constraint to determine retry behaviour
  ;;       - run-multiple
  ;;       - run-and-fail
  ;;       - run-and-skip
  ;; TODO: Default query for resolver should be constraint query

  ;; Flatten out constraints

  (def example-constraint
    {:id [:some :id]
     :query {:a [:a]
             :b [:path :to :b]}
     :pred (fn [{:keys [a b]}]
             (< a b))
     :resolver (fn [{:keys [a b]}]
                 {:a (dec b)})})

  (def example-event
    "rename?"
    {:id [:some :id]
     :query {:a [:a]
             :b [:path :to :b]
             :user-name [::ctx :userid]}
     :return {:a [:a]
              :c [:path :to :c]}
     :fn (fn [{:keys [a b user-name]} {old-a :a old-c :c :as old}]
           {:a (str user-name b)})})

  (def eg-schema
    "Note, this would be generated from a more idiomatic/readable syntax, akin to the model declaration from domino."
    {:model [[:a+b=c
              [:a {:id             :a
                   :val-if-missing 1}]
              [:b {:id             :b
                   :val-if-missing 0}]
              [:c {:id             :c
                   :val-if-missing 1}]]
             [:collatz
              [:x {:id             :x
                   :val-if-missing 1}]]
             #_
             [:patients {:collection-id :patients
                         :index [:mrn]}
              [:mrn {:id :mrn
                     :read-only? true}]
              [:name {:id :name}]
              [:first {:id :fname}]
              [:last {:id :lname}]]]

     :constraints
     [#_
      {:id :stringify-name
       :target {:patients :*}
       :query {:f :fname
               :l :lname
               :n :name}
       :pred (fn [{:keys [f l n]}]
               (= n (str l ", " f)))
       :resolver {:query {:f :fname
                          :l :lname}
                  :return {:n [:patients {:mrn :*} :name]}
                  :fn (fn [{:keys [f l]}]
                        {:n (str l ", " f)})}}
      {:id    :a-is-valid?
       :query {:a :a}
       :pred  (fn [{:keys [a]}]
                (and (integer? a)
                     (odd? a)))}
      {:id    :b-is-valid?
       :query {:b :b}
       :pred  (fn [{:keys [b]}]
                (and (integer? b)))}
      {:id    :x-is-even-or-one?
       :query {:x :x}
       :pred  (fn [{:keys [x]}]
                (or (= 1 x)
                    (even? x)))
       :resolver
       {:query  {:x :x}
        :return {:x [:collatz :x]}
        :fn     (fn [{:keys [x]}]
                  {:x (+ (* x 3) 1)})}}
      {:id    :x-is-odd-or-one?
       :query {:x :x}
       :pred  (fn [{:keys [x]}]
                (or (= 1 x)
                    (odd? x)))
       :resolver
       {:query  {:x :x}
        :return {:x [:collatz :x]}
        :fn     (fn [{:keys [x]}]
                  {:x (quot x 2)})}}

      {:id       :compute-c
       :query    {:a :a
                  :b :b
                  :c :c}
       :pred     (fn [{:keys [a b c]}]
                   (= c (+ a b)))
       :resolver {:query  {:a :a :b :b}
                  :return {:c [:a+b=c :c]}
                  :fn     (fn [{:keys [a b]}]
                            {:c (+ a b)})}}]})

  (def eg-db
    {:collatz {:x 3}
     :a+b=c {:a 1 :c 0}
     :patients [{:mrn 0}
                {:mrn 1
                 :name "Foo"}]})

  (defn get-db [ctx]
    (-> ctx ::rx ::db :value))

  (defn transact [{state ::rx :as ctx} changes]
    (println "\n\nTransacting... ")
    (clojure.pprint/pprint (get-in state [::db :value]))
    (clojure.pprint/pprint changes)
    (let [append-db-hash! (fn [ctx] (let [hashes (::db-hashes ctx #{})
                                          h (hash (get-db ctx))]
                                      (if (hashes h)
                                        (throw (ex-info "Repeated DB state within transaction!"
                                                        {:hashes hashes
                                                         ::db (get-db ctx)}))
                                        (do (println h " -- " hashes)
                                            (update ctx ::db-hashes (fnil conj #{}) h)))))
          {state ::rx :as ctx} (-> ctx
                                   (update ::rx rx/update-root update ::db #(reduce
                                                                             (fn [db [path value]]
                                                                               ;; TODO: add special path types/segments/structures for advanced change types
                                                                               ;;       e.g. transposition, splicing, etc.
                                                                               (assoc-in db path value))
                                                                             %
                                                                             changes))
                                   append-db-hash!)]
      (println "Applied changes. Got:")
      (clojure.pprint/pprint (get-in state [::db :value]))
      (println "Checking for conflicts...")
      ;; TODO: decide if this is guaranteed to halt, and either amend it so that it is, or add a timeout.
      ;;       if a timeout is added, add some telemetry to see where cycles arise.
      (let [{::keys [unresolvable resolvers]
             :as conflicts} (rx/get-reaction state ::conflicts)]
        (println (rx/get-reaction state ::conflicts))
        (if (empty? conflicts)
          (println "No conflicts!")
          (do
            (println "Conflicts found:")
            (clojure.pprint/pprint conflicts)))
        (cond
          (empty? conflicts)
          (do
            (println "Transaction finished successfully!")
            (clojure.pprint/pprint (get-in state [::db :value]))
            (dissoc ctx ::db-hashes))

          (not-empty unresolvable)
          (throw (ex-info "Unresolvable constraint conflicts exist!"
                          {:unresolvable unresolvable
                           :conflicts conflicts}))

          ;; TODO: aggregate compatible changes from resolvers, or otherwise synthesize a changeset from a group of resolvers.
          (not-empty resolvers)
          (let [resolver-id (first (vals resolvers))
                {state ::rx :as ctx} (update ctx ::rx rx/compute-reaction resolver-id)
                changes (rx/get-reaction state resolver-id)]
            (println "[DEBUG(println)] - attempting resolver: " resolver-id " ... changes: " changes)
            (recur ctx changes))

          :else
          (throw (ex-info "Unexpected key on :domino/conflicts. Expected :domino.core/resolvers, :domino.core/passed, and/or :domino.core/unresolvable."
                          {:unexpected-keys (remove #{::resolvers ::passed ::unresolvable} (keys conflicts))
                           :conflicts conflicts
                           :reaction-definition (get state ::conflicts)}))))))

  (def default-reactions [{:id ::db
                           :args ::rx/root
                           :fn ::db}
                          ;; NOTE: send hash to user for each change to guarantee synchronization
                          ;;       - Also, for each view declared, create a sub for the sub-db it cares about
                          ;;       - Also, consider keeping an aggregate of transactions outside of ::db (or each view) to enable looking up an incremental changeset, rather than a total refresh
                          ;;       - This is useful, because if a hash corresponds to a valid ::db on the server, it can be used to ensure that the client is also valid without using rules.
                          ;;       - Also, we can store *just* the hash in the history, rather than the whole historical DB.
                          ;; NOTE: locks could be subscriptions that use hash and could allow for compare-and-swap style actions
                          {:id [::db :util/hash]
                           :args ::db
                           :fn hash}])


  ;; TODO: flatten constraint!
  ;; TODO: update query/resolver interaction so that paths are reverse engineerable and/or stored once dynamic paths are supported

  #_{:id [:some :id]
     :query {:a [:a]
             :b [:path :to :b]}
     :pred (fn [{:keys [a b]}]
             (< a b))
     :resolver (fn [{:keys [a b]}]
                 {:a (dec b)})}

  (defn passing-constraint-result? [result]
    (or (true? result) (nil? result)))

  (defn constraint->reactions [constraint]
    (let [resolver (:resolver constraint)
          resolver-id [::resolver (:id constraint)]
          pred-reaction (cond->
                            {:id [::constraint (:id constraint)]
                             ::is-constraint? true
                             ::has-resolver? (boolean resolver)
                             :args-format :map
                             :args (:query constraint)
                             :fn
                             (:pred constraint)}
                          ;; TODO: handle dynamic query stuff
                          resolver (->(assoc ::resolver-id resolver-id)
                                      (update :fn (partial
                                                   comp
                                                   #(if (passing-constraint-result? %)
                                                      %
                                                      resolver-id)))))]

      (cond-> [pred-reaction]
        resolver (conj
                  {:id [::resolver (:id constraint)]
                   :args-format :map
                   :lazy? true
                   :args (:query resolver)
                   :fn (comp
                        (fn [result]
                          (reduce-kv
                           (fn [changes ret-k path]
                             (if (contains? result ret-k)
                               (conj changes [path (get result ret-k)])
                               changes))
                           []
                           (:return resolver)))
                        (:fn resolver))}))))

  (defn model-map-merge [opts macc m]
    ;; TODO: custom merge behaviour for privileged keys.
    ;;       Possibly also provide a multimethod to allow custom merge beh'r for user specified keys.
    (merge macc m))

  (defn clean-macc [opts m]
    (select-keys m (into [] (:inherited-keys opts))))

  (defn parse-segment [segment]
    ;; TODO: parse collection ID segments
    ;; TODO: parse no-op segment
    ;; TODO: parse query-style segments (e.g. first, nth...)
    ;;        - e.g. first-where (i.e. (some #(when (pred %) %) coll)) segments (if desired?)
    nil)

  #_
  {:id [:x]
   :args ::db
   :fn #(:x % 0)}

  (defn lookup-reaction [path m]
    (if (:path-params m)
      ;; TODO: allow for parametrized reactions to support `:path-params`
      (throw (ex-info "Not Implemented!"
                      {:path path
                       :m    m}))
      (cond-> {:id (:id m)
               :args ::db
               :args-format :single
               :fn #(get-in % path (:val-if-missing m))}
        ;; TODO: check for nearest parent rx-id and use instead of `::db`
        )))


  (defn walk-model
    "This parses the model into a set of reactions.
  The model is a vector of nodes, and each node is a segment, an optional map, and zero or more children. "
    [raw-model {:as opts}]
    (letfn [(walk-node [parent-path macc [segment & [m? :as args]]]
              (let [[m children] (if (map? m?)
                                   [(model-map-merge opts macc m?) (rest args)]
                                   [macc args])
                    ;; TODO parse-segment should handle params etc.
                    path ((fnil conj []) parent-path (or (parse-segment segment) segment))
                    next-macc (clean-macc opts m)]
                (cond-> []
                  (:id m)               (conj (lookup-reaction path m))
                  (not-empty children) (into
                                        (reduce (fn [acc child] (into acc (walk-node path next-macc child))) [] children))
                  ;; TODO: Add validation constraint parsing
                  ;; TODO: Add dispatch/expansion/compilation on path and/or m for a custom reaction collection
                  )))]
      (reduce
       (fn [acc sub-model]
         (into acc (walk-node [] {} sub-model)))
       []
       raw-model)))

  (defn parse-reactions [schema]
    (-> default-reactions
        ;; TODO Add reactions parsed from model
        ;; TODO Add reactions parsed from other places (e.g. events/constraints)
        (into (walk-model (:model schema) {}))
        (into (:reactions schema))
        (into (mapcat constraint->reactions (:constraints schema)))))
  #_
  {:id :compute-c
   :query {:a [:a]
           :b [:b]
           :c [:c]}
   :pred (fn [{:keys [a b c] :or {a 0 b 0 c 0}}]
           (= c (+ a b)))
   :resolver {:query {:a [:a] :b [:b]}
              :return {:c [:c]}
              :fn (fn [{:keys [a b]}]
                    {:c (+ a b)})}}


  (defn get-constraint-ids [state]
    (keep (fn [[k v]]
            (when (::is-constraint? v)
              k))
          state))

  (defn add-conflicts-rx! [state]
    (rx/add-reaction! state {:id ::conflicts
                             :args-format :rx-map
                             :args (vec (get-constraint-ids state))
                             :fn (fn [preds]
                                   ;; NOTE: Should this be made a lazy seq?
                                   (let [conflicts
                                         (reduce-kv
                                          (fn [m pred-id result]
                                            (cond
                                              ;;TODO: add other categories like not applicable or whatever comes up
                                              (or (true? result) (nil? result))
                                              (update m ::passed (fnil conj #{}) pred-id)

                                              (false? result)
                                              (update m ::unresolvable (fnil assoc {}) pred-id {:id pred-id
                                                                                                :message (str "Constraint predicate " pred-id " failed!")})

                                              (and (vector? result) (= (first result) ::resolver))
                                              (update m ::resolvers (fnil assoc {}) pred-id result)

                                              :else
                                              (update m ::unresolvable (fnil assoc {}) pred-id (if (and (map? result)
                                                                                                        (contains? result :message)
                                                                                                        (contains? result :id))
                                                                                                 result
                                                                                                 {:id pred-id
                                                                                                  :message (str "Constraint predicate " pred-id " failed!")
                                                                                                  :result result}))))
                                          {}
                                          preds)]
                                     (when (not-empty (dissoc conflicts ::passed))
                                       conflicts)))}))

  (defn initialize
    ([schema] (initialize schema {}))
    ([schema initial-db]
     ;; TODO: parse queries into reactions
     (let [state (-> (rx/create-reactive-map {::db initial-db})
                     (rx/add-reactions!
                      (parse-reactions schema))
                     (add-conflicts-rx!))]
       (transact {::rx state} [])))))

;; ==============================================================================
;; OLD VERSION
;; ==============================================================================

#?(:clj
   (comment
     (defmacro event [[_ in out :as args] & body]
       (let [in-ks#  (mapv keyword (:keys in))
             out-ks# (mapv keyword (:keys out))]
         {:inputs  in-ks#
          :outputs out-ks#
          :handler `(fn ~(vec args) ~@body)}))))
#_
(defn transact
  "Take the context and the changes which are an ordered collection of changes

  Assumes all changes are associative changes (i.e. vectors or hashmaps)"
  [ctx changes]
  (let [updated-ctx (events/execute-events ctx changes)]
    (effects/execute-effects! updated-ctx)
    updated-ctx))
#_
(defn initial-transaction
  "If initial-db is not empty, transact with initial db as changes"
  [{::keys [model] :as ctx} initial-db]
  (if (empty? initial-db)
    ctx
    (transact ctx
              (reduce
                (fn [inputs [_ path]]
                  (if-some [v (get-in initial-db path)]
                    (conj inputs [path v])
                    inputs))
                []
                (:id->path model)))))
#_
(defn initialize
  "Takes a schema of :model, :events, and :effects

  1. Parse the model
  2. Inject paths into events
  3. Generate the events graph
  4. Reset the local ctx and return value

  ctx is a map of:
    ::model => a map of model keys to paths
    ::events => a vector of events with pure functions that transform the state
    ::effects => a vector of effects with functions that produce external effects
    ::state => the state of actual working data
    "
  ([schema]
   (initialize schema {}))
  ([{:keys [model effects events] :as schema} initial-db]
   ;; Validate schema
   (validation/maybe-throw-exception (validation/validate-schema schema))
   ;; Construct ctx
   (let [model  (model/model->paths model)
         ;; TODO: Generate trivial events for all paths with `:pre` or `:post` and no event associated.
         events (model/connect-events model events)]
     (initial-transaction
       {::model         model
        ::events        events
        ::events-by-id  (util/map-by-id events)
        ::effects       (effects/effects-by-paths (model/connect-effects model effects))
        ::effects-by-id (util/map-by-id effects)
        ::db            initial-db
        ::graph         (graph/gen-ev-graph events)}
       initial-db))))
#_
(defn trigger-effects
  "Triggers effects by ids as opposed to data changes

  Accepts the context, and a collection of effect ids"
  [ctx effect-ids]
  (transact ctx (effects/effect-outputs-as-changes ctx effect-ids)))
