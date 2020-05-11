(ns domino.core
  (:require
   [domino.effects :as effects]
   [domino.events :as events]
   [domino.graph :as graph]
   [domino.model :as model]
   [domino.validation :as validation]
   [domino.util :as util]
   [domino.rx :as rx]))


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



(defn walk-model [raw-model {:as opts}]
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
     (transact {::rx state} []))))

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
