(ns domino.core
  (:require
   [domino.effects :as effects]
   [domino.events :as events]
   [domino.graph :as graph]
   [domino.model :as model]
   [domino.validation :as validation]
   [domino.util :as util :refer [dissoc-in]]
   [domino.rx :as rx]
   [clojure.pprint]))

(defn compute-path [ctx id]
  ((::id->path ctx) id))

(defn is-collection? [ctx]
  (::collection? ctx))

(defn create-new-context-in-collection [ctx idx cb]
  ((::create-element ctx) idx cb))


(declare transact)

(defn- resolve-changes-impl
  [ctx [change :as changes] on-success on-fail]
  (if (empty? changes)
    (on-success ctx)
    (case (first change)
      ::set-value
      (let [[_ id val] change]
        (if-some [p (compute-path ctx id)]
          (recur (update ctx ::db assoc-in p val) (rest changes) on-success on-fail)
          (on-fail (ex-info (str "No path found for id: " id)
                            {:id id}))))
      ::remove-value
      (recur
       (update ctx ::db dissoc-in (compute-path ctx (second change)))
       (rest changes)
       on-success
       on-fail)

      ::update-child
      ;;TODO: Aggregate transaction reports somehow
      ;;TODO: unwrap long child-ctx paths in parse change
      (let [[_ [id idx?] & child-changes] change
            sub-context ((::subcontexts ctx) id)
            collection? (is-collection? sub-context)
            ;;TODO: Instead of duplicating context on creation, consider doing a merge.
            child-ctx (if collection?
                        (get-in sub-context [::elements idx?])
                        sub-context)
            subctx-id (if collection? [id idx?] [id])
            tx-cb (fn [{child-v ::db
                        tx ::transaction-report
                        :as new-child-ctx}]
                    (if (= :failed (:status tx))
                      (on-fail (ex-info (:message (:data tx)) (update (:data tx) :subcontext-id (partial into [(if collection? [id idx?] [id])]))))
                      (-> ctx
                          (assoc-in (if collection?
                                      [::subcontexts id ::elements idx?]
                                      [::subcontexts id])
                                    new-child-ctx)
                          (update ::db #(let [p (cond-> (compute-path ctx id)
                                                  collection? (conj idx?))]
                                          (if (empty? child-v)
                                            (dissoc-in % p)
                                            (assoc-in % p
                                                      child-v))))
                          (update ::child-tx-reports
                                  (fnil assoc {}) subctx-id tx)
                          (resolve-changes-impl (rest changes) on-success on-fail))))]
        (if (nil? child-ctx)
          (create-new-context-in-collection sub-context idx? #(transact % child-changes tx-cb))
          (transact child-ctx child-changes tx-cb)))

      ::remove-child
      (let [[_ [id idx]] change]
        (-> ctx
            (update-in [::subcontexts id ::elements] dissoc idx)
            (update ::db dissoc-in (conj (compute-path ctx id) idx))
            (recur (rest changes) on-success on-fail)))

      ;; ELSE
      (on-fail (ex-info "Invalid change!"
                      {:id ::invalid-change
                       :message "Invalid change!"
                       :change change})))))

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

(defn include-child-tx-reports [ctx]
  (if-some [children (::child-tx-reports ctx)]
    (-> ctx
        (update ::transaction-report assoc ::children children)
        (dissoc ::child-tx-reports))
    ctx))

(defn resolve-changes [ctx changes post-cb]
  ;;TODO: manage change-parsing and transaction annotation from `transact` fn
  ;; consider compiling the transaction-report from ephemeral keys assoc'd here (i.e. middleware style)
  (try
    (let [parsed-changes (parse-changes ctx changes)]
      (resolve-changes-impl ctx parsed-changes
                            (fn on-success
                              [ctx]
                              (-> ctx
                                  (update ::transaction-report (fnil assoc {}) :status :complete)
                                  (include-child-tx-reports)
                                  (update-in
                                   [::transaction-report :changes]
                                   (fnil into [])
                                   parsed-changes)
                                  (post-cb)))
                            (fn on-fail
                              [ex]
                              (let [data (ex-data ex)]
                                (case (:id data)
                                  ::invalid-change
                                  (post-cb
                                   (assoc ctx ::transaction-report {:status :failed
                                                                    :reason ::invalid-change
                                                                    :data   data}))
                                  (throw ex))))))
    (catch #?(:clj Exception :cljs js/Error) ex
      (let [data (ex-data ex)]
        (post-cb
         (assoc ctx ::transaction-report {:status :failed
                                          :reason ::change-parsing-failed
                                          :data   data}))))))

(defn get-db [ctx]
  (-> ctx ::rx ::db :value))

(defn transact
  ([ctx user-changes]
   (if (::async? ctx)
     (throw (ex-info "Schema is async, but no callback was provided!"
                     {:schema (::schema ctx)
                      :async? (::async? ctx)}))
     (transact ctx user-changes identity)))
  ([{db-pre ::db :as ctx-pre} user-changes cb]
   (letfn [(rollback!
             [ctx]
             (-> ctx-pre
                 (dissoc ::db-hashes
                         ::event-queue)
                 (assoc ::transaction-report (::transaction-report ctx))
                 cb))
           (append-db-hash!
             [ctx]
             (let [hashes (::db-hashes ctx #{})
                   h (hash (get-db ctx))]
               (if (hashes h)
                 (throw (ex-info "Repeated DB state within transaction!"
                                 {:id ::cyclic-transaction
                                  :hashes hashes
                                  ::db (get-db ctx)}))
                 (update ctx ::db-hashes (fnil conj #{}) h))))
           (get-triggered-events
             [{state ::rx  events ::events :as ctx}]
             (->> (rx/get-reaction state ::events)
                  (remove
                   (fn [ev]
                     ;;TODO: drop events that should be ignored.
                     false))
                  not-empty))
           (append-events-to-queue
             [ctx]
             (let [triggered (get-triggered-events ctx)]
               (update ctx ::event-queue
                       (fnil (partial reduce conj) events/empty-queue)
                       triggered)))
           (handle-changes!
             [ctx changes post-cb]
             (resolve-changes ctx changes
                              (fn [{::keys [db transaction-report] :as ctx'}]
                                (if (= :failed (:status transaction-report))
                                  (rollback! ctx')
                                  (try
                                    (-> ctx'
                                        (update ::rx rx/update-root assoc ::db db)
                                        (append-events-to-queue)
                                        append-db-hash!
                                        post-cb)
                                    (catch #?(:clj Exception
                                              :cljs js/Error) ex
                                      (case (:id (ex-data ex))
                                        ::cyclic-transaction
                                        (rollback! (assoc ctx' ::transaction-report {:status :failed
                                                                                     :reason ::cyclic-transaction
                                                                                     :data (ex-data ex)}))
                                        ;;else
                                        (rollback! (assoc ctx' ::transaction-report {:status :failed
                                                                                     :reason ::unknown-error
                                                                                     :data (ex-data ex)})))))))))
           (post-tx
             [{::keys [rx transaction-report] :as ctx}]
             (let [fxs (if (= :complete (:status transaction-report))
                         (rx/get-reaction rx ::effects)
                         [])]
               (doseq [fx fxs
                       :let
                       [run-fx! (rx/get-reaction rx fx)]]
                 ;; Evaluate each effect fn
                 (when (fn? run-fx!) (run-fx!)))
               (cb
                (-> ctx
                    (dissoc
                     ::db-hashes
                     ::event-queue)
                    (assoc
                     ::triggered-effects fxs)))))
           (tx-step
             [ctx changes]
             (handle-changes!
              ctx changes
              (fn [{state ::rx
                    db    ::db
                    ev-q  ::event-queue
                    tx-report ::transaction-report
                    :as ctx}]
                (let [;; 2. Identify any conflicts that have arisen.
                      {::keys [unresolvable resolvers]
                       :as conflicts}
                      (rx/get-reaction state ::conflicts)]
                  ;; TODO: decide if this is guaranteed to halt, and either amend it so that it is, or add a timeout.
                  ;;       if a timeout is added, add some telemetry to see where cycles arise.
                  (cond
                    (= :failed (:status tx-report))
                    (rollback! ctx)

                    (and (empty? conflicts) (empty? ev-q))
                    (do
                      (post-tx ctx))

                    (not-empty unresolvable)
                    (rollback! (assoc ctx
                                      ::transaction-report
                                      {:status :failed
                                       :reason ::unresolvable-constraint
                                       :data
                                       {:unresolvable unresolvable
                                        :conflicts conflicts}}))

                    ;; TODO: Ensure that resolvers can operate in parallel
                    (not-empty resolvers)
                    (let [resolver-ids (vals resolvers)
                          {state ::rx :as ctx} (update ctx ::rx #(reduce rx/compute-reaction % resolver-ids))
                          changes (reduce (fn [acc resolver] (into acc (rx/get-reaction state resolver))) [] resolver-ids)]
                      (tx-step ctx changes))

                    (not-empty conflicts)
                    (rollback! (assoc ctx ::transaction-report
                                      {:status :failed
                                       :reason ::invalid-conflicts
                                       :data {:unexpected-keys (remove #{::resolvers ::passed ::unresolvable} (keys conflicts))
                                              :conflicts conflicts
                                              :reaction-definition (get state ::conflicts)}}))

                    :else
                    (letfn [(trigger-event [q c]
                              (tx-step (-> ctx
                                           (assoc ::event-queue (pop q))
                                           (update ::event-history (fnil conj []) (peek q)))
                                       [c]))
                            ;; Pass state rx-map to allow use of `compute-reaction` for caching
                            (run-next-event [state q]
                              (if (empty? q)
                                (do
                                  (post-tx ctx))
                                (let [ev-id (peek q)
                                      state (rx/compute-reaction state ev-id)
                                      c (rx/get-reaction state ev-id)]
                                  (cond
                                    (nil? c)
                                    (recur state (pop q))
                                    (fn? c)
                                    (do
                                      (c (fn [r]
                                           (if (nil? r)
                                             (run-next-event state (pop q))
                                             (trigger-event q r)))))
                                    :else
                                    (trigger-event q c)))))]
                      (run-next-event state ev-q)))))))]
     (tx-step (-> ctx-pre
                  (dissoc ::transaction-report ::db-hashes ::triggered-effects)           ;; Clear tx-report and db-hashes
                  (update ::rx rx/update-root assoc ::db-pre db-pre)) ;; Add db-pre for event triggering
              user-changes))))                                        ;; Start tx with user driven changes.

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
                        {:id ::db-pre
                         :args ::rx/root
                         :fn ::db-pre}
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
                 ::upstream (rx/args->inputs :map (:query constraint))
                 ::downstream (vals (:return constraint))
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

(defn add-event-to-state! [state {:keys [id inputs outputs ctx-args handler async? ignore-changes should-run] :as event}]
  ;; TODO: support events that traverse context boundary
  ;; TODO: support ctx-args on rx
  (-> state
      (rx/add-reaction! {:id [::inputs id]
                         :args inputs
                         :args-format :rx-map
                         :fn identity})
      (rx/add-reaction! {:id [::inputs-pre id]
                         :args (mapv (partial conj [::pre]) inputs)
                         :args-format :rx-map
                         :fn identity})
      (rx/add-reaction! {:id [::trigger? id]
                         :args (->> inputs
                                    (remove (set ignore-changes))
                                    (mapv (partial conj [::changed?])))
                         :args-format :vector
                         :fn #(when (some true? %&)
                                id)})
      (rx/add-reaction! {:id [::outputs id]
                         :args outputs
                         :args-format :rx-map
                         :fn identity})
      (rx/add-reaction! {:id [::outputs-pre id]
                         :args (mapv (partial conj [::pre]) outputs)
                         :args-format :rx-map
                         :fn identity})
      (rx/add-reaction! {:id [::ctx-args id]
                         :args ctx-args
                         :args-format :rx-map
                         :fn identity})
      (rx/add-reaction! {:id id
                         ::is-event? true
                         ::upstream (->> inputs
                                         (remove (set ignore-changes)))
                         ::downstream outputs
                         :lazy? true
                         :args {:ctx-args [::ctx-args id]
                                :inputs [::inputs id]
                                :outputs [::outputs id]
                                :inputs-pre [::inputs-pre id]
                                :outputs-pre [::outputs-pre id]}
                         :args-format :map
                         :fn
                         (letfn [(process-result [r o]
                                   (reduce-kv
                                    (fn [acc k v]
                                      (if (not= (get r k v) v)
                                        (assoc acc k (get r k))
                                        acc))
                                    nil
                                    o))]
                           (fn [a]
                             (when (or (not (ifn? should-run)) (should-run a))
                               (if async?
                                 (fn [cb]
                                   (handler
                                    a
                                    (fn [result]
                                      (cb (process-result result (:outputs a))))))
                                 (process-result (handler a) (:outputs a))))))})))

(defn add-event-rxns! [state events]
  (->
   (reduce
    add-event-to-state!
    state
    events)
   (rx/add-reaction! {:id ::events
                      :args-format :vector
                      :args (mapv #(conj [::trigger?] (:id %)) events)
                      :fn (fn [& evs]
                            (not-empty
                             (filter some? evs)))})))

(defn add-outgoing-effect-to-state! [state {:keys [id inputs handler] :as effect}]
  (-> state
      (rx/add-reaction! {:id [::inputs id]
                         :args inputs
                         :args-format :rx-map
                         :fn identity})
      (rx/add-reaction! {:id [::trigger? id]
                         :args (->> inputs
                                    (mapv (partial conj [::changed?])))
                         :args-format :vector
                         :fn #(when (some true? %&)
                                id)})
      (rx/add-reaction! {:id id
                         ::is-effect? true
                         :lazy? true
                         :args {:inputs [::inputs id]}
                         :args-format :map
                         :fn
                         (fn [{:keys [inputs]}]
                           (fn []
                             (handler {:inputs inputs})))})))

(defn add-incoming-effect-to-state! [state {:keys [id outputs handler] :as effect}]
  (-> state
      (rx/add-reaction! {:id [::outputs id]
                         :args outputs
                         :args-format :rx-map
                         :fn identity})
      (rx/add-reaction! {:id id
                         ::is-effect? true
                         :lazy? true
                         :args {:outputs [::outputs id]}
                         :args-format :map
                         :fn
                         (fn [a]
                           (fn [args]
                             (let [r (handler (assoc a :args args))]
                               (reduce-kv
                                (fn [acc k v]
                                  (if (not= (get r k v) v)
                                    (assoc acc k (get r k))
                                    acc))
                                nil
                                (:outputs a)))))})))

(defn add-effect-rxns! [state effects]
  (let [incoming-effects (filter #(seq (:outputs %)) effects)
        outgoing-effects (filter #(seq (:inputs %)) effects)]
    (rx/add-reaction!
     (reduce
      add-incoming-effect-to-state!
      (reduce
       add-outgoing-effect-to-state!
       state
       outgoing-effects)
      incoming-effects)
     {:id ::effects
      :args-format :vector
      :args (mapv #(conj [::trigger?] (:id %)) outgoing-effects)
      :fn (fn [& fx]
            (not-empty
             (filter some? fx)))})))


(declare initialize)

(defn add-fields-to-ctx [{::keys [db] :as ctx} [field :as fields] on-complete]
  (if (empty? fields)
    (on-complete ctx)
    (let [[id {::keys [path] :keys [collection? schema index-id] :as m}] field
          ctx (cond-> ctx
                path (->
                      (update ::id->path (fnil assoc {}) id path)
                      (update ::reactions (fnil into []) [{:id id
                                                           :args ::db
                                                           :args-format :single
                                                           :fn #(get-in % path (:val-if-missing m))}
                                                          {:id [::pre id]
                                                           :args ::db-pre
                                                           :args-format :single
                                                           :fn #(get-in % path (:val-if-missing m))}
                                                          {:id [::changed? id]
                                                           :args [id [::pre id]]
                                                           :args-format :vector
                                                           :fn #(not= %1 %2)}]))
                (not-empty m) (update ::id->opts (fnil assoc {}) id m))]
      (cond
        (and schema collection?)
        (letfn [(create-element
                  ([idx cb]
                   (create-element idx {} cb))
                  ([idx v cb]
                   (initialize schema v (fn [{::keys [id->path] :as el}]
                                          (-> el
                                              (update ::db
                                                      assoc-in
                                                      (id->path index-id [::index])
                                                      idx)
                                              cb)))))
                (add-element-to-ctx [ctx idx v cb]
                  (create-element idx v
                                  (fn [el]
                                    (-> ctx
                                        (assoc-in [::subcontexts id ::elements idx] el)
                                        (update ::db
                                                (fn [m p v]
                                                  (if (empty? v)
                                                    (dissoc-in m p)
                                                    (assoc-in m p v)))
                                                (conj path idx)
                                                (::db el))
                                        cb))))
              (add-elements-to-ctx [ctx [[k v] :as kvs] cb]
                (if (empty? kvs)
                  (cb ctx)
                  (add-element-to-ctx ctx k v #(add-elements-to-ctx % (rest kvs) cb))))]
          (-> ctx
              (update ::subcontexts (fnil assoc {}) id
                      {::collection? true
                       ::index-id index-id
                       ::create-element create-element})
              (add-elements-to-ctx
               (vec (get-in db path))
               #(add-fields-to-ctx % (rest fields) on-complete))))

        schema
        (initialize schema (get-in db path)
                    (fn [el]
                      (-> ctx
                          (update ::subcontexts (fnil assoc {}) id el)
                          (update ::db
                                  (fn [m p v]
                                    (if (empty? v)
                                      (dissoc-in m p)
                                      (assoc-in m p v)))
                                  path
                                  (::db el))
                          (add-fields-to-ctx (rest fields) on-complete))))
        :else
        (recur ctx (rest fields) on-complete)))))

(defn deep-relationships [rel-map]
  (letfn [(add-relationship [m src ds]
            (reduce-kv
             (fn [acc k v]
               (assoc acc k
                      (if (contains? v src)
                        (into v ds)
                        v)))
             {}
             m))]
    (reduce-kv
     add-relationship
     rel-map
     rel-map)))

(defn compute-relationships [{::keys [rx] :as ctx}]
  (let [ctx'
        (reduce
         (fn [ctx {::keys [upstream downstream]}]
           (-> ctx
               (update ::downstream
                       (fn [rel]
                         (reduce
                          (fn [rel' up]
                            (update rel' up (fnil into #{}) downstream))
                          (or rel {})
                          upstream)))
               (update ::upstream
                       (fn [rel]
                         (reduce
                          (fn [rel' down]
                            (update rel' down (fnil into #{}) upstream))
                          (or rel {})
                          downstream)))))
         ctx
         (filter
          #(and (seq (::upstream %)) (seq (::downstream %)))
          (vals rx)))]
    (assoc
     ctx'
     ::upstream-deep (deep-relationships (::upstream ctx'))
     ::downstream-deep (deep-relationships (::downstream ctx')))))

(defn add-default-id [m]
  (update m :id #(or % (util/random-uuid))))

(defn normalize-schema [schema]
  (-> schema
      (update :events (partial mapv add-default-id))
      (update :effects (partial mapv add-default-id))
      (update :constraints (partial mapv add-default-id))))

(defn schema-is-async? [schema]
  (if (some :async? (:events schema))
    true
    (some
     #(some-> %
              second
              :schema
              schema-is-async?)
     (walk-model (:model schema) {}))))

(defn initialize
  ([schema]
   (initialize schema {} nil))
  ([schema cb-or-db]
   (if (map? cb-or-db)
     (initialize schema cb-or-db nil)
     (initialize schema {} cb-or-db)))
  ([schema initial-db cb]
   (try
     (let [schema (normalize-schema schema)
           fields (walk-model (:model schema) {})
           async? (boolean (schema-is-async? schema))]
       (when (and async? (nil? cb))
         (throw (ex-info "Schema is async, but no callback was provided!"
                         {:schema schema
                          :async? async?})))
       (add-fields-to-ctx
        {::db initial-db
         ::rx (rx/create-reactive-map initial-db)
         ::schema schema
         ::async? async?}
        fields
        (fn [ctx]
          (-> ctx
              (update ::rx rx/add-reactions! (into (::reactions ctx)
                                                   (parse-reactions schema)))
              (update ::rx add-conflicts-rx!)
              (update ::rx add-event-rxns! (:events schema []))
              (update ::rx add-effect-rxns! (:effects schema []))
              (compute-relationships)
              ;; NOTE: this transact will not trigger events!
              ;;       does this need to be changed?
              (transact [] (or cb identity))))))
     (catch #?(:clj Throwable
               :cljs js/Error) e
       (if (nil? cb)
         (throw e)
         (cb e))))))

(defn trigger-effects
  ([ctx triggers]
   (if (::async? ctx)
     (throw (ex-info "Schema is async, but no callback was provided!"
                     {:schema (::schema ctx)
                      :async? (::async ctx)}))
     (trigger-effects ctx triggers nil)))
  ([ctx triggers cb]
   (transact ctx (reduce
                  (fn [changes [id args]]
                    (if-some [fx-fn (rx/get-reaction (::rx ctx) id)]
                      (conj
                       changes
                       (fx-fn args))
                      changes))
                  []
                  triggers)
             (or cb identity))))

(def full-name-constraint
  {:id :compute-full-name
   :query {:f :given-name
           :l :surname
           :n :full-name}
   :return {:n :full-name}
   :pred (fn [{:keys [f l n]}]
           (= (if (or (empty? f) (empty? l))
                (or (not-empty f) (not-empty l))
                (str l ", " f))
              n))
   :resolver (fn [{:keys [f l n]}]
               {:n (if (or (empty? f) (empty? l))
                     (or (not-empty f) (not-empty l))
                     (str l ", " f))})})

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
  {:model [[:mrn {:id :mrn}]
           [:name
            [:first {:id :given-name}]
            [:last  {:id :surname}]
            [:full {:id :full-name}]]
           [:height
            [:metres {:id :h :val-if-missing 0}]
            [:inches {:id :h-in :val-if-missing 0}]]
           [:weight
            [:kilograms {:id :w :val-if-missing 0}]
            [:pounds    {:id :w-lb :val-if-missing 0}]]
           [:bmi    {:id :bmi}]
           [:async
            [:in {:id :in}]
            [:out {:id :out}]
            [:ms {:id :ms :val-if-missing 1000}]]]
   :constraints [full-name-constraint]
   :events [{:inputs [:w]
             :outputs [:w-lb]
             :handler
             (fn [{{:keys [w]} :inputs
                   {:keys [w-lb]} :outputs}]
               (when-not (< -0.01 (- (* w 2.2) w-lb) 0.01)
                 {:w-lb
                  (* w 2.2)}))}
            {:id :event/convert-w-lb
             :inputs [:w-lb]
             :outputs [:w]
             :handler
             (fn [{{:keys [w-lb]} :inputs
                   {:keys [w]}    :outputs}]
               (when-not (< -0.01 (- (* w 2.2) w-lb) 0.01)
                 {:w (/ w-lb 2.2)}))}
            {:id :event/convert-h
             :inputs [:h]
             :outputs [:h-in]
             :handler
             (fn [{{:keys [h]} :inputs
                   {:keys [h-in]} :outputs}]
               (when-not (< -0.01 (- (* h-in 0.0254) h) 0.01)
                   {:h-in (/ h 0.0254)}))}
            {:id :event/convert-h-in
             :inputs [:h-in]
             :outputs [:h]
             :handler
             (fn [{{:keys [h-in]} :inputs
                   {:keys [h]} :outputs}]
               (when-not (< -0.01 (- (* h-in 0.0254) h) 0.01)
                 {:h (* h-in 0.0254)}))}
            {:id :event/bmi
             :inputs [:h :w]
             :outputs [:bmi]
             :handler (fn [{{:keys [h w]} :inputs}]
                        (if (= 0 h)
                          {:bmi nil}
                          {:bmi (/ w h h)}))}
            {:id :event/w
             :inputs [:h :bmi]
             :ignore-changes [:h]
             :outputs [:w]
             :handler (fn [{{:keys [h bmi]} :inputs}]
                        {:w (* bmi h h)})}]
   :effects
   [{:id :fx/print-bmi
     :inputs [:bmi]
     :handler (fn [{{:keys [bmi]} :inputs}]
                (println "\n\n\n\n\nBMI:")
                (println bmi)
                (println "\n\n\n\n\n"))}
    {:id :fx/set-patient
     :outputs [:given-name :surname :h :w :in :ms]
     :handler (fn [{{:keys [f l h w in ms] :as args} :args}]
                (println args)
                {:given-name (or f "John")
                 :surname (or l "Doe")
                 :h (or h 1.85)
                 :w (or w 200)
                 :in (or in 40)
                 :ms (or ms 500)})}]})

(def example-schema-async (update example-schema-trivial :events conj
                                  {:id :event/set-out
                                   :inputs [:in :ms]
                                   :ignore-changes [:ms]
                                   :outputs [:out]
                                   :async? true
                                   :should-run (fn [{{:keys [in]} :inputs {:keys [out]} :outputs}]
                                                 (or (not= in out) (println "Skipping..." in out)))
                                   :handler (fn [{{:keys [in ms]} :inputs {:keys [out]} :outputs} cb]
                                              (println "in: " in "ms: " ms)
                                              #?(:clj
                                                 (future
                                                   (println "Sleeping for ms: " ms)
                                                   (Thread/sleep ms)
                                                   (println "WOKE UP!")
                                                   (cb {:out in}))
                                                 :cljs
                                                 (js/setTimeout
                                                  #(cb {:out in})
                                                  ms)))}))


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
             [:label {:id :label}]
             [:patient {:id :patient
                        :schema
                        example-schema-trivial}]]})

(def example-schema-1
    ""
    {:model
     [[:patients
       {:id :patients
        :index-id :mrn
        :order-by [:surname :given-name :mrn]
        :collection? true
        :schema
        example-schema-trivial}]
      [:shift
       [:start {:id :start-date}]
       [:end {:id :end-date}]
       [:length {:id :shift-length}]]]
     #_#_:events [{:inputs [:shift-length [:patient :medication :dose]] ;; [:patients "1234123" :medications "224-A" :dose]
               :outputs [[:patient :medication :unit]]}]})

(def example-schema-2
    ""
    {:model
     [[:patients
       {:id :patients
        :index-id :mrn
        :order-by [:surname :given-name :mrn]
        :collection? true
        :schema
        {:constraints [full-name-constraint]
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

;; EVENTS


;; General process
;; 1. Aggregate all things which changed in rx-map
;; 2. Based on changed things, find all triggered events
;; 3. Place events into a queue
;; 4. Take an event off of the queue, and compute it's resulting changeset
;; 5. Place any events triggered onto the queue (at the end)
;; 6. Repeat until event queue is consumed

;; Notes
;; - Configure event-specific behaviours (e.g. how many times in a tx can it be triggered?)
;; - Allow for rxns to non-db values (e.g. db-pre, ctx, etc...)
;; - Allow for writes to an ephemeral db for intermediate values

;; RELATIONSHIPS

;; - Aggregate relatedness from constraints and events at compile time
;; - Consider tracking metadata about 'why' things are related, if only for debugging
;; - Consider future ability to draw graph of relationships

;; ==============================================================================
;; TODO
;; - DONE Prevent empty subcontexts from generating a map.
;; - TODO Support cross-context paths for non-collection subcontexts
;; - Reference old version from events possibly
;; - Add relatedness fns
;; - Add event-style rules
;; - Add computation/derivation-style rules
;; - Add intermediate-value rules
;; - Ensure as much feature parity as possible
;; - Establish future enhancment paths (i.e. cross-context rules)
;; - Connect/rewrite tests as neccessary
;; - Tidy namespaces and add/update documentation


;; ==============================================================================
;; OLD VERSION
;; ==============================================================================

#?(:clj
   (defmacro event [[_ in out :as args] & body]
     (let [in-ks#  (mapv keyword (:keys in))
           out-ks# (mapv keyword (:keys out))]
       {:inputs  in-ks#
        :outputs out-ks#
        :handler `(fn ~(vec args) ~@body)})))

(defn transact-old
  "Take the context and the changes which are an ordered collection of changes

  Assumes all changes are associative changes (i.e. vectors or hashmaps)"
  [ctx changes]
  (let [updated-ctx (events/execute-events ctx changes)]
    (effects/execute-effects! updated-ctx)
    updated-ctx))

(defn initial-transaction-old
  "If initial-db is not empty, transact with initial db as changes"
  [{::keys [model] :as ctx} initial-db]
  (if (empty? initial-db)
    ctx
    (transact-old ctx
              (reduce
                (fn [inputs [_ path]]
                  (if-some [v (get-in initial-db path)]
                    (conj inputs [path v])
                    inputs))
                []
                (:id->path model)))))

(defn initialize-old
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
   (initialize-old schema {}))
  ([{:keys [model effects events] :as schema} initial-db]
   ;; Validate schema
   (validation/maybe-throw-exception (validation/validate-schema schema))
   ;; Construct ctx
   (let [model  (model/model->paths model)
         ;; TODO: Generate trivial events for all paths with `:pre` or `:post` and no event associated.
         events (model/connect-events model events)]
     (initial-transaction-old
       {::model         model
        ::events        events
        ::events-by-id  (util/map-by-id events)
        ::effects       (effects/effects-by-paths (model/connect-effects model effects))
        ::effects-by-id (util/map-by-id effects)
        ::db            initial-db
        ::graph         (graph/gen-ev-graph events)}
       initial-db))))

(defn trigger-effects-old
  "Triggers effects by ids as opposed to data changes

  Accepts the context, and a collection of effect ids"
  [ctx effect-ids]
  (transact-old ctx (effects/effect-outputs-as-changes ctx effect-ids)))
