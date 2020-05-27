(ns domino.events
  (:require
    [domino.model :as model]
    [domino.util :refer [generate-sub-paths]]
    [clojure.set :refer [union]]))

(defn get-db-paths [model db paths]
  (reduce
   (fn [id->value path]
     (let [parent (get-in db (butlast path))]
       (if (contains? parent (last path))
         (assoc id->value (model/id-for-path model path) (get-in db path))
         id->value)))
    {}
    paths))

(def empty-queue
  #?(:clj  clojure.lang.PersistentQueue/EMPTY
     :cljs cljs.core/PersistentQueue.EMPTY))

#?(:clj
   (defmethod clojure.core/print-method clojure.lang.PersistentQueue
     [queue writer]
     (.write writer (str "#<PersistentQueue: " (pr-str (vec queue)) ">"))))

(defn try-event
  ([event ctx db old-outputs] (try-event event ctx db old-outputs nil))
  ([{:keys [handler inputs] :as event} {:domino.core/keys [model] :as ctx} db old-outputs cb]
   (try
     (if cb
       (handler ctx (get-db-paths model db inputs) old-outputs
                (fn [result] (cb (or result old-outputs))))
       (or
         (handler ctx (get-db-paths model db inputs) old-outputs)
         old-outputs))
     (catch #?(:clj Exception :cljs js/Error) e
       (throw (ex-info "failed to execute event" {:event event :context ctx :db db} e))))))

(defn apply-result-to-ctx [ctx model old-outputs new-outputs]
  (reduce-kv
    (fn [ctx id new-value]
      ;;todo validate that the id matches an ide declared in outputs
      (if (not= (get old-outputs id) new-value)
        (let [path (get-in model [:id->path id])]
          (-> ctx
              (update ::changed-paths (fnil (partial reduce conj) empty-queue)
                      (generate-sub-paths path))
              (update ::db assoc-in path new-value)
              (update ::changes conj [path new-value])))
        ctx))
    ctx
    new-outputs))

(defn ctx-updater
  "Reducer that updates context with new values updated in ctx from
  handler of each edge. New values are only stored when they are different
  from old values.

  In changed cases, the following keys are updated:
  ::changed-paths => queue of affected paths
  ::db => temporary relevant db within context
  ::change-history => sequential history of changes. List of tuples of path-value pairs"
  [edges {::keys [db] :domino.core/keys [model] :as ctx}]
  (reduce
   (fn [ctx {{:keys [async? outputs] :as event} :edge}]
     (let [old-outputs (get-db-paths (:domino.core/model ctx) db outputs)]
       (if async?
         (try-event event ctx db old-outputs
                    (fn [new-outputs]
                      (apply-result-to-ctx ctx model old-outputs new-outputs)))
         (apply-result-to-ctx ctx model old-outputs (try-event event ctx db old-outputs)))))
   ctx
   edges))

(defn input? [edge]
  (= :input (:relationship edge)))

(defn origin-path [graph origin]
  (loop [origin (vec origin)]
    (or (when (empty? origin) ::does-not-exist)
        (when (contains? graph origin) origin)
        (recur (subvec origin 0 (dec (count origin)))))))

(defn eval-traversed-edges
  "Given an origin and graph, update context with edges.

  When an node has been visited (as an input), it cannot be considered for an output"
  ([{::keys [changed-paths] :as ctx} graph]
   (let [x  (peek changed-paths)
         xs (pop changed-paths)]
     ;; Select the first change to evaluate
     (eval-traversed-edges (assoc ctx ::changed-paths xs) graph x)))
  ([{::keys [changes] :as ctx} graph origin]
   ;; Handle the change (origin) passed in, and recur as needed
   (let [;; Select the relevant parent of the change
         focal-origin   (origin-path graph origin)
         ;; Get the edges of type :input for the focal-origin
         edges          (filter input? (get graph focal-origin #{}))
         ;; Get the new graph with the handled origin removed
         removed-origin (dissoc graph focal-origin)
         ;; Call `ctx-updater` to handle the changes associated with the given edge
         {::keys [changed-paths] :as new-ctx} (ctx-updater edges ctx)
         x              (peek changed-paths)
         xs             (pop changed-paths)]
     (if x
       (recur (assoc new-ctx ::changed-paths xs) removed-origin x)
       new-ctx))))

(defn execute-events [{:domino.core/keys [db graph] :as ctx} inputs]
  (let [{::keys [db changes]} (eval-traversed-edges
                                (reduce
                                  (fn [ctx [path value]]
                                    (-> ctx
                                        (update ::db assoc-in path value)
                                        (update ::changed-paths conj path)
                                        (update ::changes conj [path value])))
                                  (assoc ctx ::db db
                                             ::changed-paths empty-queue
                                             ;; ::executed-events #{}
                                             ::changes [])
                                  inputs)
                                graph)]
    (assoc ctx :domino.core/db db
               :domino.core/change-history changes)))

(defn events-inputs-as-changes [{:domino.core/keys [events-by-id db]} event-ids]
  (reduce
    (fn [changes event-id]
      (let [inputs (get-in events-by-id [event-id :inputs])]
        (concat changes
                (map (fn [path]
                       [path (get-in db path)])
                     inputs))))
    []
    event-ids))
