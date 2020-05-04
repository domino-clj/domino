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
  {:constraints
   [{:id :a-is-valid?
     :query {:a [:a]}
     :pred (fn [{:keys [a]}]
             (and (integer? a)
                  (odd? a)))}
    {:id :b-is-valid?
     :query {:b [:b]}
     :pred (fn [{:keys [b]}]
             (and (integer? b)))}
    {:id :x-is-even-or-one?
     :query {:x [:x]}
     :pred (fn [{:keys [x]}]
             (or (= 1 x)
                 (even? x)))
     :resolver
     {:query {:x [:x]}
      :return {:x [:x]}
      :fn (fn [{:keys [x]}]
            {:x (+ (* x 3) 1)})}}
    {:id :x-is-odd-or-one?
     :query {:x [:x]}
     :pred (fn [{:keys [x]}]
             (or (= 1 x)
                 (odd? x)))
     :resolver
     {:query {:x [:x]}
      :return {:x [:x]}
      :fn (fn [{:keys [x]}]
            {:x (quot x 2)})}}

    ;; query
    ;; {:a [:a ...]}
    ;; outputs
    ;; {}

    #_
    {:id :compute-b-from-a
     :query {:a [:a]}
     :pred (fn [{:keys [a] :or {a 0}}]
             (odd? a))
     :resolver {:input {:a [:a]}
                :fn (fn [{:keys [a]}]
                      {:b (* 2 a)})
                :return {:b [:b]}}}
    #_
    {:id :compute-a-from-b
     :query {:b [:b]}
     :pred (fn [{:keys [b] :or {b 0}}]
             (odd? b))
     :resolver {:query {:b [:b]}
                :return {:a [:a]}
                :fn (fn [{:keys [a]}]
                      {:b (* 2 a)})}}
    {:id :compute-c
     :query {:a [:a]
             :b [:b]
             :c [:c]}
     :pred (fn [{:keys [a b c] :or {a 0 b 0 c 0}}]
             (= c (+ a b)))
     :resolver {:query {:a [:a] :b [:b]}
                :return {:c [:c]}
                :fn (fn [{:keys [a b]}]
                      {:c (+ a b)})}}]
   :reactions
   [{:id [:x]
     :args ::db
     :fn #(:x % 0)}
    {:id [:a]
     :args ::db
     :fn #(:a % 0)} ;; NOTE: could place defaults here
    {:id [:b]
     :args ::db
     :fn #(:b % 0)}
    {:id [:c]
     :args ::db
     :fn #(:c % 0)}]})

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
                                 append-db-hash!
                                 (update ::rx rx/update-root update ::db #(reduce
                                                                           (fn [db [path value]]
                                                                             ;; TODO: add special path types/segments/structures for advanced change types
                                                                             ;;       e.g. transposition, splicing, etc.
                                                                             (assoc-in db path value))
                                                                           %
                                                                           changes)))]
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
                         :fn hash}
                        {:id ::conflicts
                         :args-format :rx-map
                         :args []
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
                                  conflicts)))}])

(defn parse-reactions [schema]
  (-> default-reactions
      ;; TODO Add reactions parsed from model
      ;; TODO Add reactions parsed from other places (e.g. events/constraints)
      (into (:reactions schema))))
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

(defn passing-constraint-result? [result]
  (or (true? result) (nil? result)))

(defn add-constraints! [rx-map constraints]
  ;; TODO add means of correlating realized path to constraint and resolver
  (reduce
   (fn [rx-map constraint]
     (let [resolver (:resolver constraint)
           pred-reaction {:id [::constraint (:id constraint)]
                          :args-format :map
                          :args (:query constraint)
                          :fn
                          (if resolver
                            (comp #(if (passing-constraint-result? %)
                                     %
                                     [::resolver (:id constraint)])
                                  (:pred constraint))
                            (:pred constraint))}
           ]
       (-> rx-map
           (rx/add-reaction! pred-reaction)
           (rx/add-reaction! (update (get rx-map ::conflicts)
                                     :args
                                     (fnil conj [])
                                     (:id pred-reaction)))
           (cond->
               resolver (rx/add-reaction! {:id [::resolver (:id constraint)]
                                           :args-format :map
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
                                                (:fn resolver))})))))
   rx-map
   constraints))

(defn initialize
  ([schema] (initialize schema {}))
  ([schema initial-db]
   ;; TODO: parse queries into reactions
   (let [state (-> (rx/create-reactive-map {::db initial-db})
                   (rx/add-reactions!
                    (parse-reactions schema))
                   (add-constraints! (:constraints schema)))]
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
