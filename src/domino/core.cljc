(ns domino.core
  (:require
   [domino.util :as util :refer [dissoc-in]]
   [domino.rx :as rx]))

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
            add-event-context (fn [subctx]
                                (update subctx ::event-context merge (::event-context ctx)))
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
          (create-new-context-in-collection sub-context idx? #(transact (add-event-context %) child-changes tx-cb))
          (transact (add-event-context child-ctx) child-changes tx-cb)))

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
                   (if-let [{::keys [collection? index-id]} (get (::subcontexts ctx) k)]
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
      (reduce
       into
       []
       (map
        (partial parse-change ctx)
        ((::custom-change-fns ctx)
         change)))

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
                                  post-cb))
                            (fn on-fail
                              [ex]
                              (let [data (ex-data ex)]
                                (case (:id data)
                                  ::invalid-change
                                  (post-cb
                                   (assoc ctx ::transaction-report {:status :failed
                                                                    :reason ::invalid-change
                                                                    :message (ex-message ex)
                                                                    :data   data}))
                                  (throw ex))))))
    (catch #?(:clj Exception :cljs js/Error) ex
      (let [data (ex-data ex)]
        (post-cb
         (assoc ctx ::transaction-report {:status :failed
                                          :reason ::change-parsing-failed
                                          :data   data
                                          :message (ex-message ex)}))))))

(defn get-db [ctx]
  (-> ctx ::rx ::db :value))

(defn transact
  ([ctx user-changes]
   (if (::async? ctx)
     (throw (ex-info "Schema is async, but no callback was provided!"
                     {:schema (::schema ctx)
                      :async? (::async? ctx)}))
     (transact ctx user-changes identity)))
  ([{db-pre? ::db event-context ::event-context initialized? ::initialized? :as ctx-pre} user-changes cb]
   ;; TODO: replace initialized? flag with `::core/set-db` change type
   (let [db-pre (when initialized? db-pre?)]
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
             (append-events-to-queue
               [{state ::rx  events ::events hist ::event-history :as ctx}]
               (let [triggered (rx/get-reaction state ::events)
                     append-event-fn (fn [ev-q ev-id]
                                       ;; TODO: handle event bubbling and pruning here
                                       (let [{:keys [evaluation
                                                     exclusions
                                                     ignore-events]
                                              :or {exclusions #{}
                                                   ignore-events #{}
                                                   evaluation :converge-weak}
                                              :as event}
                                             (get events ev-id)]
                                         #_(println
                                          "Appending..." ev-id
                                          "to " ev-q "? (" triggered ")"
                                          (or
                                           ;; Prevent self-triggering
                                           (and
                                            (and (= :converge-weak evaluation) (= (last hist) ev-id))
                                            1)
                                           ;; Prevent ignore-events triggering
                                           (and
                                            (contains? ignore-events (last hist))
                                            2)
                                           ;; Prevent exclusions in queue
                                           (and
                                            (some (partial contains? exclusions) ev-q)
                                            3)
                                           ;; Prevent exclusions or self in history
                                           (and
                                            (some #(or (exclusions %) (= ev-id %)) hist)
                                            4)
                                           ;; Prevent :first when already in queue
                                           (and
                                            (and (= :first evaluation) (some #(= ev-id %) ev-q))
                                            5)
                                           "FALSE"))
                                         (cond
                                           ;; Prune event cases.
                                           (or
                                            ;; Prevent self-triggering
                                            (and (= :converge-weak evaluation) (= (last hist) ev-id))
                                            ;; Prevent ignore-events triggering
                                            (contains? ignore-events (last hist))
                                            ;; Prevent exclusions in queue
                                            (some (partial contains? exclusions) ev-q)
                                            ;; Prevent exclusions or self in history
                                            (some #(or (exclusions %) (= ev-id %)) hist)
                                            ;; Prevent :first when already in queue
                                            (and (= :first evaluation) (some #(= ev-id %) ev-q)))
                                           ev-q

                                           ;; Bubble :once instead of standard triggering behaviour.
                                           (= :once evaluation)
                                           (conj
                                            (into
                                             util/empty-queue
                                             (remove #(= % ev-id))
                                             ev-q)
                                            ev-id)

                                           ;; Default event triggered behaviour
                                           :else
                                           (conj ev-q ev-id))))]
                 (update ctx ::event-queue
                         (fnil (partial reduce append-event-fn) util/empty-queue)
                         triggered)))
             (handle-changes!
               [ctx changes post-cb]
               (resolve-changes ctx changes
                                (fn [{::keys [db transaction-report] :as ctx'}]
                                  (if (= :failed (:status transaction-report))
                                    (rollback! ctx')
                                    (try
                                      (-> ctx'
                                          (update ::rx rx/update-root
                                                  (fn [{db-pre ::db :as root}]
                                                    (assoc root
                                                           ::db db
                                                           ::db-pre db-pre)))
                                          (append-events-to-queue)
                                          append-db-hash!
                                          post-cb)
                                      (catch #?(:clj Exception
                                                :cljs js/Error) ex
                                        (case (:id (ex-data ex))
                                          ::cyclic-transaction
                                          (rollback! (assoc ctx' ::transaction-report
                                                             {:status :failed
                                                              :reason ::cyclic-transaction
                                                              :data (ex-data ex)}))
                                          ;;else
                                          (rollback! (assoc ctx' ::transaction-report
                                                             {:status :failed
                                                              :reason ::unknown-error
                                                              :message (ex-message ex)
                                                              :data (ex-data ex)
                                                              :error
                                                              ex})))))))))
             (post-tx
               [{::keys [rx transaction-report event-history] :as ctx}]
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
                       ::event-queue
                       ::event-history)
                      (assoc
                       ::triggered-effects fxs)
                      (update ::transaction-report
                              #(cond-> %
                                 (seq fxs) (assoc :triggered-effects fxs)
                                 (seq event-history) (assoc :event-history event-history)))))))
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
                      (rollback! (update ctx ::transaction-report
                                         merge
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
                      (rollback! (update ctx ::transaction-report
                                         merge
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
                                  (post-tx ctx)
                                  (let [ev-id (peek q)
                                        state (rx/compute-reaction state ev-id)
                                        c (rx/get-reaction state ev-id)]
                                    #_(println "ev-id: " ev-id "Result: " c)
                                    (cond
                                      (nil? c)
                                      (recur state (pop q))
                                      (fn? c)
                                      (c (fn [r]
                                           (if (nil? r)
                                             (run-next-event state (pop q))
                                             (trigger-event q r))))
                                      :else
                                      (trigger-event q c)))))]
                        #_(println "Events..." ev-q )
                        (run-next-event state ev-q)))))))]
       #_(println "Transacting... " (::db ctx-pre) "Changes..." user-changes)
       (tx-step (-> ctx-pre
                    (dissoc ::transaction-report ::db-hashes ::triggered-effects)           ;; Clear tx-report and db-hashes
                    (cond->
                        (not initialized?) append-events-to-queue)
                    (assoc ::initialized? true)
                    (update ::rx rx/update-root
                            assoc
                            ::db-pre   db-pre
                            ::db-start db-pre
                            ::ctx event-context)) ;; Add db-pre for event triggering
                user-changes)))))                                        ;; Start tx with user driven changes.

(defn model-map-merge [opts macc m]
  ;; TODO: custom merge behaviour for privileged keys.
  ;;       Possibly also provide a multimethod to allow custom merge beh'r for user specified keys.
  (merge macc m))

(defn clean-macc [opts {:keys [id] :as m}]
  (-> m
      (select-keys (into [] (:inherited-keys opts)))
      (cond->
          (some? id) (update :parents (fnil conj #{}) id))))

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
                        {:id ::ctx
                         :args ::rx/root
                         :fn ::ctx}
                        {:id ::db-pre
                         :args ::rx/root
                         :fn ::db-pre}
                        {:id ::db-start
                         :args ::rx/root
                         :fn ::db-start}
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
      (cond->
          (seq ctx-args) (rx/add-reaction! {:id [::ctx-args id]
                                            :args ::ctx
                                            :args-format :single
                                            :fn (fn [ctx]
                                                  (select-keys ctx ctx-args))}))
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
      (rx/add-reaction! {:id id
                         ::is-event? true
                         ::upstream (->> inputs
                                         (remove (set ignore-changes)))
                         ::downstream outputs
                         :lazy? true
                         :args (cond-> {:inputs [::inputs id]
                                        :outputs [::outputs id]
                                        :inputs-pre [::inputs-pre id]
                                        :outputs-pre [::outputs-pre id]}
                                 (seq ctx-args) (assoc :ctx-args [::ctx-args id]))
                         :args-format :map
                         :fn
                         (letfn [(drop-nils [m]
                                   (into {}
                                         (filter
                                          (comp some? val))
                                         m))
                                 (process-args [a]
                                   (-> a
                                       (update :inputs drop-nils)
                                       (update :outputs drop-nils)
                                       (update :inputs-pre drop-nils)
                                       (update :outputs-pre drop-nils)
                                       (update :ctx-args drop-nils)))
                                 (process-result [r o]
                                   (reduce-kv
                                    (fn [acc k v]
                                      (if (not= (get r k v) v)
                                        (assoc acc k (get r k))
                                        acc))
                                    nil
                                    o))]
                           (fn [{:keys [outputs] :as a}]
                             (let [a (process-args a)]
                               (when (or (not (ifn? should-run)) (should-run a))
                                 (if async?
                                   (fn [cb]
                                     (handler
                                      a
                                      (fn [result]
                                        (cb (process-result result outputs)))))
                                   (process-result (handler a) outputs))))))})))

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

(defn add-outgoing-effect-to-state! [state {:keys [id inputs ctx-args handler] :as effect}]
  (-> state
      (cond->
          (seq ctx-args) (rx/add-reaction! {:id [::ctx-args id]
                                            :args ::ctx
                                            :args-format :single
                                            :fn (fn [ctx]
                                                  (select-keys ctx ctx-args))}))
      (rx/add-reaction! {:id [::inputs id]
                         :args inputs
                         :args-format :rx-map
                         :fn identity})
      (rx/add-reaction! {:id [::trigger? id]
                         :args (->> inputs
                                    (mapv (partial conj [::changed-ever?])))
                         :args-format :vector
                         :fn #(when (some true? %&)
                                id)})
      (rx/add-reaction! {:id id
                         ::is-effect? true
                         :lazy? true
                         :args (cond->
                                   {:inputs [::inputs id]}
                                 (seq ctx-args) (assoc :ctx-args [::ctx-args id]))
                         :args-format :map
                         :fn
                         (fn [{:keys [inputs ctx-args]}]
                           (fn []
                             (handler {:inputs inputs
                                       :ctx-args ctx-args})))})))

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

(defn schema-is-async? [schema]
  (boolean (some :async? (:events schema))))

(defn add-fields-to-ctx [{::keys [db] :as ctx} [field :as fields] on-complete]
  (if (empty? fields)
    (on-complete ctx)
    (let [[id {::keys [path] :keys [collection? parents schema index-id] :as m}] field
          ctx (cond-> ctx
                (not-empty m) (update ::id->opts (fnil assoc {}) id m)
                path (->
                      (update ::id->parents (fnil assoc {}) id (or parents #{}))
                      (update ::id->path (fnil assoc {}) id path)
                      (update ::reactions (fnil into []) [{:id id
                                                           :args ::db
                                                           :args-format :single
                                                           :fn #(get-in % path (:val-if-missing m))}
                                                          {:id [::pre id]
                                                           :args ::db-pre
                                                           :args-format :single
                                                           :fn #(get-in % path (:val-if-missing m))}
                                                          {:id [::start id]
                                                           :args ::db-start
                                                           :args-format :single
                                                           :fn #(get-in % path (:val-if-missing m))}
                                                          {:id [::changed? id]
                                                           :args [id [::pre id]]
                                                           :args-format :vector
                                                           :fn #(not= %1 %2)}
                                                          {:id [::changed-ever? id]
                                                           :args [id [::start id]]
                                                           :args-format :vector
                                                           :fn #(not= %1 %2)}]))
                (and schema (schema-is-async? schema)) (assoc ::async? true))]
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
  (update m :id #(or % (keyword "UUID" (str (util/random-uuid))))))

(defn normalize-schema [schema]
  (-> schema
      (update :events (partial mapv add-default-id))
      (update :effects (partial mapv add-default-id))
      (update :constraints (partial mapv add-default-id))))

(defn initialize
  ([schema]
   (initialize schema {} nil))
  ([schema cb-or-db]
   (if (map? cb-or-db)
     (initialize schema cb-or-db nil)
     (initialize schema {} cb-or-db)))
  ([schema initial-db cb]
   #_(println "Initializing...")
   (try
     (let [schema (normalize-schema schema)
           fields (walk-model (:model schema) {})]
       (add-fields-to-ctx
        {::db initial-db
         ::rx (rx/create-reactive-map {::db initial-db})
         ::schema schema
         ::async? (schema-is-async? schema)
         ::event-context (:event-context schema)}
        fields
        (fn [{::keys [async?] :as ctx}]
          (when (and async? (nil? cb))
            (throw (ex-info "Schema is async, but no callback was provided!"
                            {:schema schema
                             :async? async?})))
          (-> ctx
              (assoc ::events (apply sorted-map (mapcat (juxt :id identity) (:events schema []))))
              (update ::rx rx/add-reactions! (into (::reactions ctx [])
                                                   (parse-reactions schema)))
              (update ::rx add-conflicts-rx!)
              (update ::rx add-event-rxns! (:events schema []))
              (update ::rx add-effect-rxns! (:effects schema []))
              (compute-relationships)
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
                  (fn [changes fx]
                    (let [[id args] (if (coll? fx) fx [fx nil])]
                      (if-some [fx-fn (rx/get-reaction (::rx ctx) id)]
                        (conj
                         changes
                         (fx-fn args))
                        changes)))
                  []
                  triggers)
             (or cb identity))))

(defn select
  [{::keys [subcontexts rx db] :as ctx} id]
  ;; TODO: Implement selection from subcontexts.
  (cond
    (or (nil? id) (and (coll? id) (empty? id)))
    db

    (empty? ctx)
    nil

    (and (keyword? id) (contains? rx id))
    (rx/get-reaction rx id)

    (vector? id)
    (if-some [{::keys [collection? elements] :as sub} (get subcontexts (first id))]
      (if collection?
        (select (get elements (second id)) (subvec id 2))
        (select sub (subvec id 1)))
      (select ctx (first id)))

    :else
    nil))
