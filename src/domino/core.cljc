(ns domino.core
  (:require
   [domino.util :as util :refer [dissoc-in]]
   [domino.context.helpers :as helper]
   [clojure.set :refer [union]]
   [domino.rx :as rx]
   #_[#?(:clj  com.wsscode.async.async-clj
       :cljs com.wsscode.async.async-cljs)
    :as wa
    :refer [go-promise <?]]))

(defn create-new-context-in-collection [ctx idx cb]
  ((:domino.core/create-element ctx) idx cb))

(declare initialize)

(defn create-element
  ([subcontext index]
   (create-element subcontext index {}))
  ([subcontext index db]
   ;; TODO: Improve this initialization to reuse `pre-initialize` keys
   (let [el (initialize (::schema subcontext) db)]
     (update el
             ::db
             assoc-in
             ((::id->path el) (::index-id subcontext) [::index])
             index))))


(declare initialize-async)

(defn create-element-async
  [subcontext index db on-success on-fail]
  (initialize-async (::schema subcontext) db
                    (fn [el]
                      (-> el
                          (update ::db
                                  assoc-in
                                  ((::id->path el) (::index-id subcontext) [::index])
                                  index)
                          (on-success)))
                    on-fail))

;; ------------------------------------------------------------------------------
;; Change Parsing

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
    (let [[_ id v] change]
      (cond
        (nil? v)
        (parse-change ctx [::remove-value id])

        (vector? id)
        (cond
          (contains? (::subcontexts ctx) (first id))
          (parse-change
           ctx
           (if (::collection?
                (get (::subcontexts ctx) (first id)))
             [::update-child (subvec id 0 2) [::set-value (subvec id 2) v]]
             [::update-child (subvec id 0 1) [::set-value (subvec id 1) v]]))

          (and (= 1 (count id)) (contains? (::id->path ctx) (first id)))
          (parse-change
           ctx
           [::set-value (first id) v]))

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
      (and (keyword? (first change)) (helper/compute-path ctx (first change)))
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

(defn valid-change? [change]
  (and
   ;; Must begin with fundamental operations since it has already been parsed
   (#{::set-value ::remove-value ::update-child ::remove-child}
    (first change))))

;; ------------------------------------------------------------------------------
;; Change Handling

(declare pre-initialize initialize-impl post-initialize post-initialize-async initial-transact initial-transact-async transact transact-async)

(defn prevent-change-to-final! [ctx change]
  (let [id (if (#{::set-value ::remove-value} (first change))
             (second change)
             (first (second change)))]
    (if (helper/is-final? ctx id)
      (if (helper/ignore-final? ctx id)
        nil
        (throw
         (ex-info (str "Attempted to change final field: " id)
                  {:id id
                   :opts (helper/trimmed-opts ctx id)
                   :change change})))
      change)))

(defn- update-child-impl [ctx [id idx?] child-changes]
  (let [sub-context ((::subcontexts ctx) id)
        collection? (helper/is-collection? sub-context)
        child-ctx? (if collection?
                     (get-in sub-context [::elements idx?])
                     sub-context)
        subctx-id (if collection? [id idx?] [id])
        child-element-path (if collection?
                             [::subcontexts id ::elements idx?]
                             [::subcontexts id])
        child-db-path (cond-> (helper/compute-path ctx id)
                        collection? (conj idx?))
        child-ctx-pre  (-> child-ctx?
                           (or (create-element idx?))
                           (update ::event-context merge (::event-context ctx))
                           (assoc ::absolute-id  (into (::absolute-id ctx []) subctx-id)))
        child-ctx (cond-> child-ctx-pre
                    ;; NOTE: possible async here.
                    (nil?  child-ctx?) (->
                                        (assoc ::pending-changes (parse-changes child-ctx-pre child-changes))
                                        initial-transact)
                    (some? child-ctx?) (transact child-changes))
        child-db (::db child-ctx)
        child-tx-report (::transaction-report child-ctx)]
    ;; NOTE: in error handling, consider child contexts.
    (-> ctx
        (assoc-in child-element-path child-ctx)
        (update ::db (fn [db]
                       (if (empty? child-db)
                         (dissoc-in db child-db-path)
                         (assoc-in  db child-db-path child-db))))
        (update-in [::transaction-report :changes]
                   (fnil conj []) {:change (into [::update-child subctx-id] child-changes)
                                   :id     subctx-id
                                   :status :complete
                                   :report child-tx-report}))))

(defn- update-child-impl-async [ctx [id idx?] child-changes on-success on-fail]
  (let [sub-context ((::subcontexts ctx) id)
        collection? (helper/is-collection? sub-context)
        child-ctx? (if collection?
                     (get-in sub-context [::elements idx?])
                     sub-context)
        subctx-id (if collection? [id idx?] [id])
        child-element-path (if collection?
                             [::subcontexts id ::elements idx?]
                             [::subcontexts id])
        child-db-path (cond-> (helper/compute-path ctx id)
                        collection? (conj idx?))
        wrapped-success (fn [child-ctx]
                          (let [child-db        (::db child-ctx)
                                child-tx-report (::transaction-report child-ctx)]
                            (-> ctx
                                (assoc-in child-element-path child-ctx)
                                (update ::db (fn [db]
                                               (if (empty? child-db)
                                                 (dissoc-in db child-db-path)
                                                 (assoc-in  db child-db-path child-db))))
                                (update-in [::transaction-report :changes]
                                           (fnil conj []) {:change (into [::update-child subctx-id] child-changes)
                                                           :id     subctx-id
                                                           :status :complete
                                                           :report child-tx-report})
                                (on-success))))
        wrapped-fail (fn [child-error] (on-fail
                                        (ex-info "Failed to update child."
                                                 {:subcontext id
                                                  :element-id idx?}
                                                 child-error)))]
    (if (nil? child-ctx?)
      (create-element-async sub-context idx? {}
                            (fn [child-ctx-pre]
                              (-> child-ctx-pre
                                  (update ::event-context merge (::event-context ctx))
                                  (assoc ::absolute-id  (into (::absolute-id ctx []) subctx-id))
                                  (assoc ::pending-changes (parse-changes child-ctx-pre child-changes))
                                  (initial-transact-async wrapped-success wrapped-fail)))
                            wrapped-fail)

      (-> child-ctx?
          (update ::event-context merge (::event-context ctx))
          (assoc ::absolute-id  (into (::absolute-id ctx []) subctx-id))
          (transact-async child-changes wrapped-success wrapped-fail)))))

(defn- resolve-change-impl
    [ctx change]
  (case (first change)
    ::set-value
    (let [[_ id v] change]
      (if-some [p (helper/compute-path ctx id)]
        (-> ctx
            (update ::db assoc-in p v)
            (update-in [::transaction-report :changes]
                       (fnil conj [])
                       {:change change
                        :id     id
                        :status :complete}))
        (throw
         (ex-info (str "No path found for id: " id)
                  {:id id}))))

    ::remove-value
    (let [[_ id] change]
      (if-some [p (helper/compute-path ctx id)]
        (-> ctx
            (update ::db dissoc-in p)
            (update-in [::transaction-report :changes]
                       (fnil conj [])
                       {:change change
                        :id     id
                        :status :complete}))
        (throw
         (ex-info (str "No path found for id: " id)
                  {:id id}))))

    ::update-child
    (let [subctx-id (second change)]
      (update-child-impl ctx subctx-id (drop 2 change)))

    ::remove-child
    (let [[_ [id idx]] change]
      (-> ctx
          (update-in [::subcontexts id ::elements] dissoc idx)
          (update ::db dissoc-in (conj (helper/compute-path ctx id) idx))
          (update-in [::transaction-report :changes]
                     (fnil conj []) {:change change
                                     :id     [id idx]
                                     :status :complete})))))

;; NOTE: consider collapsing `resolve-changes-impl` and `resolve-changes` into a single async-friendly reducer to simplify errors & call stack.


(defn resolve-change
  [ctx-pre change]
  (if (valid-change? change)
    (resolve-change-impl ctx-pre change)
   (throw
    (ex-info "Invalid change!"
             {:id ::invalid-change
              :message "Invalid change!"
              :change change}))))


;; ------------------------------------------------------------------------------
;; Transaction Handling

(def transaction-state-keys
  [::transaction-report ::db-hashes ::triggered-effects])


(defn clear-transaction-data [ctx]
  (apply dissoc ctx transaction-state-keys))

(defn append-events-to-queue
  "Add any pending events (which aren't ignored) to the event queue.
   Events with `:evaluation` `:once` will bubble to the tail of the queue."
  [{hist ::event-history
    event-queue ::event-queue
    :as ctx}]
    (let [triggered
          (->>
           (rx/get-reaction (::rx ctx) ::events)
           (map (or (::events ctx) {}))
           (remove (fn [{ev-id :id
                         :keys [evaluation
                                exclusions
                                ignore-events]
                         :or {exclusions #{}
                              ignore-events #{}
                              evaluation :converge-weak}
                         :as event}]
                     (or
                      ;; Prevent self-triggering
                      (and (= :converge-weak evaluation) (= (last hist) ev-id))
                      ;; Prevent ignore-events triggering
                      (contains? ignore-events (last hist))
                      ;; Prevent exclusions in queue
                      (some (partial contains? exclusions) event-queue)
                      ;; Prevent exclusions or self in history
                      (some #(or (exclusions %) (= ev-id %)) hist)
                      ;; Prevent :first when already in queue
                      (and (= :first evaluation) (some #(= ev-id %) event-queue))))))
          append-events (map :id triggered)
          prune-events  (set
                         (keep
                          (fn [e]
                            (when (= :once (:evaluation e))
                              (:id e)))
                          triggered))]
      (cond-> ctx
        (not-empty prune-events)
        (update ::event-queue (partial into util/empty-queue (remove prune-events)))
        (not-empty append-events)
        (update ::event-queue (fnil into util/empty-queue) append-events))))

(defn append-db-hash!
  "Add a new db hash to the set of encountered hashes, error if duplicate is found."
  [ctx]
  (let [hashes (::db-hashes ctx #{})
        h (hash (rx/get-reaction (::rx ctx) ::db))]
    (if (hashes h)
      (throw (ex-info "Repeated DB state within transaction!"
                      {:id ::cyclic-transaction
                       :hashes hashes
                       ::db (rx/get-reaction (::rx ctx) ::db)}))
      (update ctx ::db-hashes (fnil conj #{}) h))))

(defn update-rx [ctx]
  (update ctx ::rx rx/update-root!
          (fn [{db-pre ::db :as root}]
            (assoc root
                   ::db (::db ctx)
                   ::db-pre db-pre))))

(defn db-changed? [ctx]
  (not= (::db ctx) (rx/get-reaction (::rx ctx) ::db)))


(defn get-resolution-changeset! [ctx]
  (let [{:keys [unresolvable
                resolvers]
         :as conflicts} (rx/get-reaction (::rx ctx) ::conflicts)]
    (cond
      (not-empty unresolvable)
      (throw (ex-info "Encountered unresolvable constraint violation"
                      {:status :failed
                       :reason ::unresolvable-constraint
                       :data
                       {:unresolvable unresolvable
                        :conflicts conflicts}}))

      (not-empty conflicts)
      (reduce
       (fn [acc [_ resolver]]
         (into acc (rx/get-reaction (::rx ctx) resolver)))
       []
       resolvers)
      :else
      [])))

(defn trigger-output-effects! [ctx]
  (let [rx (::rx ctx)]
    (doall
      (keep
       (fn [fx-id]
         (let [run-fx! (rx/get-reaction rx fx-id)]
           (when (fn? run-fx!)
             (run-fx!)
             fx-id)))
       (rx/get-reaction rx ::effects)))))

(defn resume-transaction
  [{db-pre ::db
    event-context ::event-context
    changes ::pending-changes
    :as ctx-pre}]
  ;; DO THE TRANSACTION

  (if (not-empty changes)
    ;; 1. resolve changes

    (-> ctx-pre
        (assoc ::pending-changes (rest changes))
        (resolve-change (first changes))
        ;; NOTE: recur may be replaced by `resume-transaction` continuation
        (recur))
    ;; 2. Handle the results of change resolution
    (let [ctx
          (if (db-changed? ctx-pre)
            (-> ctx-pre
                ;; 2a) Update reactive-map's `::db` and `::db-pre` to recompute relevant reactions.
                (update-rx)
                ;; 2b) reads the `::events` special reaction and adds triggered events to the queue
                (append-events-to-queue)
                ;; 2c) Adds the latest db-hash, erroring if duplicate
                (append-db-hash!))
            ctx-pre)
          ;; 3. Check Conflicts and Events
          resolution-changeset (get-resolution-changeset! ctx)
          event-queue          (::event-queue ctx)]
      (cond
        ;; Resolve conflicts if they exist.
        (not-empty resolution-changeset)
        (-> ctx
            (assoc ::pending-changes
                   (parse-changes ctx resolution-changeset))
            (recur))
        ;; Resolve an event if it is present
        (not-empty event-queue)
        (let [event-id (peek event-queue)
              c (rx/get-reaction (::rx ctx) event-id)]
          ;; NOTE: this is where async may be needed. Currently, event returns a fn.
          ;; TODO: async!
          (cond-> ctx
            (some? c)  (->
                        (update ::event-history   (fnil conj []) (peek event-queue))
                        (assoc  ::pending-changes (parse-changes ctx [c])))
            true       (->
                        (update ::event-queue pop)
                        (recur))))
        ;; Otherwise, we're done!
        :else
        (let [triggered-effects (trigger-output-effects! ctx) ;; NOTE: consider failure case for async here
              event-history     (::event-history ctx)]
          (-> ctx
              (dissoc ::db-hashes
                      ::event-queue
                      ::event-history
                      ::pending-changes)
              (assoc
               ::triggered-effects triggered-effects)
              (update ::transaction-report
                      #(cond-> %
                         (seq triggered-effects) (assoc :triggered-effects
                                                        triggered-effects)
                         (seq event-history)     (assoc :event-history
                                                        event-history)
                         :true (assoc :status :complete)))))))))


(declare transact-async initialize-async)

(defn change-is-async? [ctx change]
  (or
   ;; Updating an async child context
   (and (= ::update-child (first change))
        (-> ctx ::subcontexts (first (second change)) ::async?))))

(defn resolve-change-async [ctx change on-success on-fail]
  (cond
    (and (= ::update-child (first change))
         (-> ctx ::subcontexts (first (second change)) ::async?))
    (update-child-impl-async ctx (second change) (drop 2 change) on-success on-fail)

    :else
    (on-success (resolve-change ctx change))))

(defn resume-transaction-async
  [{db-pre ::db
    event-context ::event-context
    changes ::pending-changes
    :as ctx-pre}
   on-success
   on-fail]
  ;; DO THE TRANSACTION

  (if (not-empty changes)
    ;; 1. resolve changes
    (if (change-is-async? ctx-pre (first changes))
      (resolve-change-async
       (assoc ctx-pre ::pending-changes (rest changes))
       (first changes)
       #(resume-transaction-async % on-success on-fail) on-fail)
      (-> ctx-pre
          (assoc ::pending-changes (rest changes))
          (resolve-change (first changes))
          ;; NOTE: recur may be replaced by `resume-transaction` continuation
          (recur on-success on-fail)))
    ;; 2. Handle the results of change resolution
    (let [ctx
          (if (db-changed? ctx-pre)
            (-> ctx-pre
                ;; 2a) Update reactive-map's `::db` and `::db-pre` to recompute relevant reactions.
                (update-rx)
                ;; 2b) reads the `::events` special reaction and adds triggered events to the queue
                (append-events-to-queue)
                ;; 2c) Adds the latest db-hash, erroring if duplicate
                (append-db-hash!))
            ctx-pre)
          ;; 3. Check Conflicts and Events
          resolution-changeset (get-resolution-changeset! ctx)
          event-queue          (::event-queue ctx)]
      (cond
        ;; Resolve conflicts if they exist.
        (not-empty resolution-changeset)
        (-> ctx
            (assoc ::pending-changes
                   (parse-changes ctx resolution-changeset))
            (recur on-success on-fail))
        ;; Resolve an event if it is present
        (not-empty event-queue)
        (let [event-id (peek event-queue)
              c (rx/get-reaction (::rx ctx) event-id)]
          ;; NOTE: this is where async may be needed. Currently, event returns a fn.
          ;; TODO: async!
          (if (fn? c)
            (on-fail (ex-info "NOT IMPLEMENTED" {:event-id event-id}))
            (cond-> ctx
              (some? c)  (->
                          (update ::event-history   (fnil conj []) (peek event-queue))
                          (assoc  ::pending-changes (parse-changes ctx [c])))
              true       (->
                          (update ::event-queue pop)
                          (recur on-success on-fail)))))
        ;; Otherwise, we're done!
        :else
        (let [triggered-effects (trigger-output-effects! ctx) ;; NOTE: consider failure case for async here
              event-history     (::event-history ctx)]
          (-> ctx
              (dissoc ::db-hashes
                      ::event-queue
                      ::event-history
                      ::pending-changes)
              (assoc
               ::triggered-effects triggered-effects)
              (update ::transaction-report
                      #(cond-> %
                         (seq triggered-effects) (assoc :triggered-effects
                                                        triggered-effects)
                         (seq event-history)     (assoc :event-history
                                                        event-history)
                         :true (assoc :status :complete)))
              (on-success)))))))

(defn apply-db-initializers [db inits]
  (reduce
   (fn [db init]
     (init db))
   db
   inits))

(defn initialize-db [ctx initial-db]
  (let [db (apply-db-initializers initial-db (::db-initializers ctx))]
    (-> ctx
        (assoc ::db db)
        (update ::rx rx/reset-root! {::db       db
                                     ::db-pre   nil
                                     ::db-start nil}))))

(defn initialize-subcontext [ctx subctx-id]
  ;; TODO: Use create-element(-async) to add all the initial-db subcontexts and run events etc.
  ;; NOTE: Reference old add-elements-to-ctx letfn binding
  )

(defn initial-transact [ctx db]
  (-> ctx
      (initialize-db db)
      clear-transaction-data
      append-events-to-queue
      (update ::rx rx/update-root!
              assoc
              ::ctx (::event-context ctx))
      (resume-transaction)))

(defn initial-transact-async [ctx db on-success on-fail]
  (-> ctx
      (initialize-db db)
      clear-transaction-data
      append-events-to-queue
      (update ::rx rx/update-root!
              assoc
              ::ctx (::event-context ctx))
      (resume-transaction-async on-success on-fail)))

(defn transact
  ([{db-pre ::db event-context ::event-context :as ctx-pre} user-changes]
   (-> ctx-pre
       clear-transaction-data

       (update ::rx rx/update-root!
               assoc
               ;; ::db-pre preserves db state from beginning of each transaction step.
               ::db-pre   db-pre
               ;; ::db-start preserves db state from beginning of transaction
               ::db-start db-pre
               ;; ::ctx allows for reactions to pull data from the context itself
               ::ctx event-context)
       (assoc ::pending-changes (parse-changes ctx-pre user-changes))
       (resume-transaction))))

(defn transact-async
  ([{db-pre ::db event-context ::event-context :as ctx-pre} user-changes on-success on-fail]
   (-> ctx-pre
       clear-transaction-data

       (update ::rx rx/update-root!
               assoc
               ;; ::db-pre preserves db state from beginning of each transaction step.
               ::db-pre   db-pre
               ;; ::db-start preserves db state from beginning of transaction
               ::db-start db-pre
               ;; ::ctx allows for reactions to pull data from the context itself
               ::ctx event-context)
       (assoc ::pending-changes (parse-changes ctx-pre user-changes))
       (resume-transaction-async on-success on-fail))))



;; ------------------------------------------------------------------------------
;; Initialization
;; ------------------------------------------------------------------------------

;; Schema Parsing
;; --------------

(defn model-map-merge [opts macc m]
  ;; TODO: custom merge behaviour for privileged keys.
  ;;       Possibly also provide a multimethod to allow custom merge beh'r for user specified keys.
  (merge macc m))

(def always-inherit-ks [:parents :final? :ignore-updates?])

(defn clean-macc [opts {:keys [id] :as m}]
  (-> m
      (select-keys (into always-inherit-ks (:inherited-keys opts)))
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

(defn get-constraint-ids [rx-map]
  (filter #(and (vector? %) (not (empty? %)) (= (% 0) ::constraint))
          (keys (.reactions rx-map))))

(defn add-conflicts-rx! [rx-map]
  (rx/add-reaction! rx-map {:id ::conflicts
                            :args-format :rx-map
                            :args (vec (get-constraint-ids rx-map))
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
                         :args
                         (into {}
                               (map (juxt identity (partial conj [::pre])))
                               inputs)

                         :args-format :map
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
                         :args (into {}
                                     (map (juxt identity (partial conj [::pre])))
                                     outputs)
                         :args-format :map
                         :fn identity})
      (rx/add-reaction! {:id id
                         ::is-event? true
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


(declare initialize-impl)

(defn schema-is-async? [schema]
  (boolean (some :async? (:events schema))))

(defn add-fields-to-ctx [{::keys [subcontexts] :as ctx} [field :as fields]]

  ;; Reduces over fields
  ;;
  (if (empty? fields)
    ctx
    (let [[id {::keys [path] :keys [collection? parents schema index-id] :as m}] field
          ;; TODO: rewrite to use subcontext
          {::keys [subcontexts] :as ctx}
          (cond-> ctx
                path (update ::reactions (fnil into []) [{:id id
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
                                                          :fn #(not= %1 %2)}]))]
      ;; TODO: If it's a collection, we need a subcontext map under `[::subcontexts <id>]` with a create-element fn.
      ;; TODO: If it's a singleton subcontext, we need a subcontext map under `[::subcontexts <id>]` with some sort of `initialize` fn.
      ;; NOTE: Both of these need a helper for updating the parent db/ctx with canonical info, or change handling needs to maintain consistency.
      (recur ctx (rest fields))
#_
      (cond
        (and schema collection?)
        (letfn [(create-element
                  ([idx]
                   (create-element idx {}))
                  ([idx v]
                   (let [el (initialize schema v)]

                     (update el
                             ::db
                             assoc-in
                             ((::id->path el) index-id [::index])
                             idx))))
                (add-element-to-ctx [ctx idx v]
                  (let [el (create-element idx v)]
                    (-> ctx
                        (assoc-in [::subcontexts id ::elements idx] el)
                        (update ::db
                                (fn [m p v]
                                  (if (empty? v)
                                    (dissoc-in m p)
                                    (assoc-in m p v)))
                                (conj path idx)
                                (::db el)))))
                (add-elements-to-ctx [ctx kvs]
                  (reduce
                   (fn [acc [k v]]
                     (add-element-to-ctx acc k v))
                   ctx
                   kvs))]
          (-> ctx
              (update ::subcontexts update id assoc
                      ::create-element create-element)

              (add-elements-to-ctx
               (vec (get-in db path)))
              (recur (rest fields))))

        schema
        (let [el (initialize schema (get-in db path))]
          (-> ctx
              (update ::subcontexts (fnil assoc {}) id el)
              (update ::db
                      (fn [m p v]
                        (if (empty? v)
                          (dissoc-in m p)
                          (assoc-in m p v)))
                      path
                      (::db el))
              (recur (rest fields))))
        :else
        ))))

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

(defn add-default-id [m]
  (update m :id #(or % (keyword "UUID" (str (util/random-uuid))))))

(defn normalize-schema [schema]
  (-> schema
      (update :events (partial mapv add-default-id))
      (update :effects (partial mapv add-default-id))
      (update :constraints (partial mapv add-default-id))))


(defn- add-db-fn [ctx path f]
  (update ctx ::db-initializers (fnil conj []) #(update-in % path f)))

(defn- coll-subctx-db-initializer [index-id-path]
  (fn [els]
    (if (map? els)
      els
      (into {}
            (map
             (juxt
              #(get-in %
                       index-id-path)
              identity))
            els))))

(defn pre-initialize-fields
  [ctx fields]
  (reduce
   (fn [ctx [id {::keys [path] :keys [collection? parents schema index-id initialize initial-value default-value final?] :as m}]]
     (let [subctx (when schema (pre-initialize schema))]
       (cond-> ctx
         (not-empty m) (update ::id->opts (fnil assoc {}) id m)
         path (->
               (update ::id->parents (fnil assoc {}) id (or parents #{}))
               (update ::id->path (fnil assoc {}) id path)
               (cond->
                   default-value            (add-db-fn path #(if (some? %) % default-value))
                   initial-value            (add-db-fn path (fn [_] initial-value))
                   initialize               (add-db-fn path initialize)
                   (and schema collection?) (add-db-fn ctx path (coll-subctx-db-initializer (get-in subctx [::id->path index-id]))))
               ;; Intentionally skipping `::reactions` (see line 876)
               )
         (and schema (schema-is-async? schema)) (assoc ::async? true)
         schema (update ::subcontexts (fnil assoc {}) id
                        (cond-> subctx
                          true        (assoc ::absolute-path (into (::absolute-path ctx []) path))
                          collection? (assoc ::collection? true
                                             ::index-id index-id))))))
   ctx
   fields))

(defn compute-rels [rels]
  (letfn [(merge-rel [rel from-ids to-ids]
            (reduce
             (fn [agg from]
               (update agg from (comp set (fnil into #{})) to-ids))
             (or rel {})
             from-ids))]
    (reduce
     (fn [agg {::keys [upstream downstream]}]
       (-> agg
           (update ::downstream
                   merge-rel upstream downstream)
           (update ::upstream
                   merge-rel downstream upstream)))
     {::upstream {}
      ::downstream {}}
     rels)))

(defn constraint->rels [constraint]
  {::upstream (set (rx/args->inputs :map (:query constraint)))
   ::downstream (set (vals (:return constraint)))})

(defn event->rels [{:keys [id inputs outputs ctx-args handler async? ignore-changes should-run] :as event}]
  {::upstream (->> inputs
                   (remove (set ignore-changes))
                   set)
   ::downstream  (set outputs)})

(defn pre-initialize-rels [ctx schema]
  (let [{::keys [upstream downstream]} (compute-rels
                                        (concat
                                         (map constraint->rels (:constraints schema))
                                         (map event->rels (:events schema))
                                         (keep #(not-empty
                                                 (select-keys % [::upstream ::downstream]))
                                               (:reactions schema))))]
    (assoc ctx
           ::upstream upstream
           ::downstream downstream
           ::upstream-deep (deep-relationships upstream)
           ::downstream-deep (deep-relationships downstream))))

(defn pre-initialize [schema]
  (let [schema (normalize-schema schema)
        fields (walk-model (:model schema) (:opts schema {}))]
    (->
     {::schema schema
      ::db-initializers []
      ::async? (schema-is-async? schema)
      ;; NOTE: document purpose for this or remove it.
      ::event-context (:event-context schema)
      ::fields fields
      ::ignore-finals? (:ignore-finals? (:opts schema))}
     (pre-initialize-fields fields)
     (pre-initialize-rels schema)
     )))



(defn- initialize-impl
  [{::keys [fields schema] :as initial-ctx}]
  (let [ctx (-> initial-ctx
                (assoc ::rx (rx/create-reactive-map {}))
                ;; TODO: Update add-fields-to-ctx
                ;;       so that it doesn't call initialize, but just calls initialize-impl instead.
                ;;       When DB is set at top level, propagate changes to subcontexts which already exist.
                (add-fields-to-ctx fields))]


    (-> ctx
        (assoc ::events (apply sorted-map (mapcat (juxt :id identity) (:events schema []))))
        (update ::rx rx/add-reactions! (into (::reactions ctx [])
                                             (parse-reactions schema)))
        (update ::rx add-conflicts-rx!)
        (update ::rx add-event-rxns! (:events schema []))
        (update ::rx add-effect-rxns! (:effects schema [])))))

(defn post-initialize
  [ctx initial-db]
  (if (::async? ctx)
    (throw (ex-info "Context is async! please use async version of called fn"
                    {:ctx (select-keys ctx [::schema ::async?])}))
    (-> ctx
        (initial-transact initial-db))))

(defn post-initialize-async
  [ctx initial-db on-success on-fail]
  (-> ctx
      (initial-transact-async on-success on-fail)))

(defn initialize
  ([schema]
   (initialize schema {}))
  ([schema initial-db]
   (-> schema
       pre-initialize
       initialize-impl
       (post-initialize initial-db))))

(defn initialize-async
  ([schema initial-db on-success on-fail]
   (-> schema
       pre-initialize
       initialize-impl
       (post-initialize-async initial-db on-success on-fail))))

(defn trigger-effects
  ([ctx triggers]
   (if (::async? ctx)
     (throw (ex-info "Schema is async, but no callback was provided!"
                     {:schema (::schema ctx)
                      :async? (::async? ctx)}))
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
  "Given an id keyword or vector, select a value from a domino context."
  [{::keys [subcontexts rx db] :as ctx} id]

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

(defn get-path!
  "Given a domino context and an id, get the path to the value in the canonical DB"
  [{::keys [id->path subcontexts]} id]
  (let [id (if (and (coll? id)
                    (= (count id) 1))
             (first id)
             id)]
    (cond
      (and (coll? id) (empty? id))
      []

      (vector? id)
      (if-some [sub (get subcontexts (first id))]
        (into (id->path (first id))
              (if (::collection? sub)
                (if-some [idx-id (get id 1)]
                  (into [idx-id] (get-path! sub (subvec id 2)))
                  [])
                (get-path! sub (subvec id 1))))
        (throw (ex-info "No Match!" {:id id})))

      :else
      (id->path id))))

(defn get-path
  "See get-path!. Returns nil on no match instead of throwing."
  [ctx id]
  (try
    (get-path! ctx id)
    (catch #?(:clj Throwable
              :cljs js/Error) e
      nil)))

(defn get-in-db
  "Looks somewhat similar to select, but doesn't support arbitrary reactions"
  [ctx id]
  (when-some [path (get-path ctx id)]
    (get-in (::db ctx) path nil)))

(defn get-parents
  "Gets all parent ids for an id (i.e. referenced IDs which wholly contain the id's data)"
  [{::keys [id->parents subcontexts] :as ctx} id]
  (if (vector? id)
    (if-some [{::keys [collection? id->parents] :as sub} (get subcontexts (first id))]
      (if collection?
        ;; TODO: add static attributes to collection map
        (into (conj (get-parents ctx (first id))
                    (first id)
                    (subvec id 0 1)
                    (subvec id 0 2))
              (map #(if (vector? %)
                      (into (subvec id 0 2) %)
                      (conj (subvec id 0 2) %)))
              (get-parents sub (subvec id 2)))
        (into (conj (get-parents ctx (first id))
                    (first id)
                    (subvec id 0 1))
              (map #(if (vector? %)
                      (into (subvec id 0 1) %)
                      (conj (subvec id 0 1) %)))
              (get-parents sub (subvec id 1))))
      (recur ctx (first id)))
    (id->parents id)))


(defn get-downstream
  "Gets all ids that could be affected by a change on the passed id."
  [{::keys [downstream-deep subcontexts] :as ctx} id]
  (if (vector? id)
    (if-some [{::keys [collection?] :as sub} (get subcontexts (first id))]
      (if collection?
        (if (contains? id 1)
          (mapv #(if (vector? %)
                   (into (subvec id 0 2) %)
                   (conj (subvec id 0 2) %))
                (get-downstream sub (subvec id 2)))
          (downstream-deep (first id)))
        (map #(if (vector? %)
                (into (subvec id 0 1) %)
                (conj (subvec id 0 1) %))
             (get-downstream sub (subvec id 1))))
      (recur ctx (first id)))
    (downstream-deep id)))

(defn get-upstream
  "Gets all ids which, if changed, may affect the passed id."
  [{::keys [upstream-deep subcontexts] :as ctx} id]
  (if (vector? id)
    (if-some [{::keys [collection?] :as sub} (get subcontexts (first id))]
      (if collection?
        (if (contains? id 1)
          (mapv #(if (vector? %)
                   (into (subvec id 0 2) %)
                   (conj (subvec id 0 2) %))
                (get-upstream sub (subvec id 2)))
          (upstream-deep (first id)))
        (map #(if (vector? %)
                (into (subvec id 0 1) %)
                (conj (subvec id 0 1) %))
             (get-upstream sub (subvec id 1))))
      (recur ctx (first id)))
    (upstream-deep id)))
