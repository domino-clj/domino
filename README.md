
<h1><img src="logo/logo.png" title="" style="margin-bottom: -10px; margin-right: 10px;" width="85px"><a href="https://github.com/domino-clj/domino">Domino</a></h1>

[![CircleCI](https://img.shields.io/circleci/build/gh/domino-clj/domino?label=CircleCI&logo=circleci&style=flat-square)](https://circleci.com/gh/domino-clj/domino) [![Clojars Project](https://img.shields.io/clojars/v/domino/core?&style=flat-square)](https://clojars.org/domino/core) [![Slack](https://img.shields.io/badge/slack-%40clojurians%2Fdomino--clj-blue?logo=slack&style=flat-square)](https://clojurians.slack.com/messages/domino-clj) [![Clojars Downloads](https://img.shields.io/clojars/dt/domino/core?color=blue&style=flat-square)](https://clojars.org/domino/core) [![GitHub Stars](https://img.shields.io/github/stars/domino-clj/domino?logo=github&style=flat-square)](https://github.com/domino-clj/domino/stargazers)


<h3 class="hidden">See <a href="https://domino-clj.github.io">here</a> for interactive documentation.</h3>

Domino is a data flow engine that helps you organize the interactions between your data model and events. Domino allows you to declare your business logic using a directed acyclic graph of events and effects. Whenever an external change is transacted to the data model, the graph determines the chain of events that will be executed, and side effects triggered as a result of the computation.

Without a way to formalize the interactions between different parts of the application, relationships in code become implicit. This results in code that's difficult to maintain because of the mental overhead involved in tracking these relationships. Domino makes the interactions between pieces of business logic explicit and centralized.

Domino explicitly separates logic that makes changes to the data model from side effectful functions. Business logic functions in Domino explicitly declare how they interact with the data model by declaring their inputs and outputs. Domino then uses these declarations to build a graphs of related events. This approach handles cascading business logic out of the box, and provides a data specification for relationships in code. Once the changes are transacted, the effectful functions are called against the new state.

## 0.4.0-pre-alpha.1

There are a number of sweeping changes coming to Domino.
For the time being, please continue using 0.3.3. The API of 0.4.0 is rough, and subject to change.
The implementation is very messy, slow, and in need of a *lot* of work.

Please note that interceptors will be deprecated in 0.4.0.

Also, please see 0.3.3 documentation below.

### New Features

Event handler signature
> Instead of passing the whole context, we've updated event handlers to accept a single map with the keys `:inputs` `:inputs-pre` `:outputs` `:outputs-pre` and `:ctx-args`.
> Additionally, `:ctx-args` must be declared explicitly.
> For now, `:ctx-args` are keys from the map `:domino.core/event-context`, but arbitrary access to the global context may be supported eventually.
> It seems safer to constrain the areas which events can depend on, but access to the global context will be added if neccessary.

Event evaluation strategy
> Events now evaluate with different strategies depending on use case.
> e.g. `:converge` events will always be triggered, and thus must eventually reach a fixed-point.
> e.g. `:first` events will be triggered once, and will only be evaluated the first time they are triggered.
> e.g. `:once` events will be triggered once as well, but any subsequent triggers while it is queued will push it to the back of the queue.

Event ignore-events
> Events can specify other events to ignore.
> e.g. you can have a pair of events, one which converts pounds to kilograms, and one which converts kilograms to pounds. If they specify each other in their `:ignore-events` sets, they will not cause each other to run.

Event exclusions
> Events can specify other events as exclusions.
> This means that if there is an exclusion in the queue or the history, the event will not be triggered in the remainder of the transaction.

Event ignore-changes
> Events can specify inputs whose changes don't cause it to trigger.
> This allows you to specify inputs which more like parameters.

Event should-run
> This is a predicate which is run with the same arguments as the handler, but will prevent the handler from running if it returns false.
> This is especially useful for long-running async events, where you can perform a quick synchronous computation to prevent an expensive async call.

Async first, and fixed async
> prior to 0.4.0, async required the event handler to block until the callback was called, otherwise, the return value would be treated as the event result. In order to fix this, sweeping changes were required, and there is a lot of callback mess, but it works as expected now. Still, if `identity` is used as a callback, and there are no async events, domino will work as before. The core functions will fallback to this behaviour if no top-level callback is provided, but will error if there is an async event.
> However, the callbacks are complex, and the call stack is quite deep. This has performance implications.

Model Attributes
> The model is now walked in a similar manner to a reitit router. It will eventually support a similar pattern of attribute inheritance/merging.
> Ideally this will allow users to provide custom modules such as coercion/validation constraint generation.
> At this point, this is just a scaffold, but this seems to be one of the main ways to make domino extensible.

Subcontexts
> Subcontexts are a way of nesting domino schemas.
> They allow you to specify a child context at a path, or more interestingly, a collection of children contexts at a path.
> This allows for form sections to be composed into a larger workflow, while isolating logic of component parts.

`Change` types
> We've added four fundamental change types: `:domino.core/set-value`, `:domino.core/remove-value`, `:domino.core/update-child`, and `:domino.core/remove-child`
> The first two are for operating on the current context by id, and operate as you would expect. See docs for details.
> `:domino.core/update-child` passes a changelist to a child context, which will manage its own transaction.
> `:domino.core/remove-child` removes a child context and cleans up any computed values.

`Change` parsing
> In addition to change types, there is a change parsing step that transforms incoming changes into the four fundamental types above.
> We've included a default custom type - `:domino.core/set` which takes a map of id to value, and unrolls it into `:domino.core/set-value` and `:domino.core/remove-value` changes for each key-value pair. It also supports nesting for referencing subcontexts.
> If there is a `:domino.core/custom-change-fns` map on the domino context, it will use the first key in the change vector to look up a parsing function
> Also, we support implicit change types if a leading keyword isn't a recognized change type.
> e.g. `[:foo "FOO"]` -> `[:domino.core/set-value :foo "FOO"]`
> e.g. `{:foo "FOO" :bar nil}` -> `[:domino.core/set {:foo "FOO" :bar nil}]` -> `[[:domino.core/set-value :foo "FOO"] [:domino.core/remove-value :bar]]`
> e.g. `[[:a :b :c] "Foo"]` -> `[:domino.core/update-child :a [:domino.core/update-child :b [:domino.core/set-value :c "Foo"]]]`

Constraints
> Constraints are a special type of event which don't have inputs and outputs, but rather a query and a predicate.
> The predicate must return true, otherwise the transaction will fail.
> Constraints may also provide a resolver fn, which will attempt to coerce the values in the query in order to pass the constraint.
> For example, a constraint may require a number to be within certain bounds, and could have a resolver which sets an invalid value to the ceiling or floor.

Transaction Report
> We've extended the `:domino.core/change-history` to the larger notion of a transaction report.
> This will include information about successful and failed transactions, including change history, status, error information, event order, etc.
> This will also be extended to include things like effects triggered, relevant ctx keys, etc.
> Note that change history will no longer be path-value, but will be a vector changes of the fundamental change types above.

domino.rx
> This is a somewhat failed experiment in capturing a reactive model without state or ratoms or the like.
> this will be reworked or replaced before 0.4.0 is released.

#### Coming Soon

- Separate `initialize`, `transact`, `trigger-effects` synchronous implementations from asynchronous ones. (of course while still maximizing code reuse.) Improve performance for both, but especially synchronous impl.
- Refactoring and cleanup of immense and messy `domino.core` namespace, pulling out new implementation into relevant namespaces and exposing useful component fns.
- Refactoring `initialize` and `transact` to allow for clearer initial transaction and avoid having a flag.
- Events which are dependent on values in child contexts or have values in child contexts as outputs
- Events which take values from the parent context (probably shouldn't feed out to the parent context except maybe via effect.)
- Intermediate computed values which aren't stored in the DB, but can be used from events, constraints, and effects. (similar to re-frame subscriptions. will reduce repetition and improve efficiency)
- Ephemeral required values to be passed in on each transaction. (e.g. user/session/timestamp etc.)
- Expansion and extensibility of model inheritance.
- Intercepting subcontext effects by parent contexts.
- `domino.core/select` fn which will handle parsing of ids and deferral of selection to subcontexts, as well as using default values and other utilities from the model (e.g. canonical ordering of children, searching/filtering of children, etc.).
- Change of type `:domino.core/set-db` for initialization. (Although possible to use in transact, not recommended!)
- Change of type `:domino.core/set-at-path` for pathwise changes. (will not be recommended for use unless absolutely neccessary.)
- Enhanced transaction failure reporting, especially for constraints and events.
- Resiliency options for events and effects (e.g. what to do if they throw)
- Add better error reporting in transaction report for constraint/event/effect failures
- Ability to specify `:event-context` for initialize's `transact` step.
- Inheritance rules for passing/merging `:event-context` to children, if it is useful for children can have their own event contexts.
- Enhancement of queries, esp. on subcontexts. Currently, collection subcontexts must include the child ID on the ID vector (e.g. [:patient "1234123" :birthdate])
- Updating readme, docs, and examples to reflect 0.4.0
- Sweeping optimizations, likely including the reworking/replacement of the `domino.rx` namespace

## Concepts

Domino consists of three main concepts:

### 1. Model

The model represents the paths within an EDN data structure. These paths will typically represent fields within a document. Each path entry is a tuple where the first value is the path segment, and the second value is the metadata associated with it. If the path is to be used for effects and/or events, the metadata must contain the `:id` key.

For example, `[:amount {:id :amount}]` is the path entry to the `:amount` key within the data model and can be referenced in your events and effects as `:amount` (defined by the `:id`). You can nest paths within each other, such as the following model definition:

```clojure
[[:patient [:first-name {:id :fname}]]]
```

### 2. Events

The events define the business logic associated with the changes of the model. Whenever a value is transacted, associated events are computed. Events are defined by three keys; an `:inputs` vector, an `:outputs` vector, and a `:handler` function.

The handler accepts three arguments: a context containing the current state of the engine, a list of the input values, and a list of the output values. The function should produce a vector of outputs matching the declared `:outputs` key. For example:

```clojure
{:inputs  [:amount]
 :outputs [:total]
 :handler (fn [ctx {:keys [amount]} {:keys [total]}]
            {:total (+ total amount)})}
```


Domino also provides a `domino.core/event` helper for declaring events, so the above
event can also be written as follows:

```clojure
(domino.core/event [ctx {:keys [amount]} {:keys [total]}]
  {:total (+ total amount)})
```

The macro requires that the `:keys` destructuring syntax is used for input and outputs, and
expands the the event map with the `:inputs` and `:outputs` keys being inferred from the
ones specified using the `:keys` in the event declaration.

It's also possible to declare async events by providing the `:async?` key, e.g:

```clojure
{:async?  true
 :inputs  [:amount]
 :outputs [:total]
 :handler (fn [ctx {:keys [amount]} {:keys [total]} callback]
            (callback {:total (+ total amount)}))}
```

Async event handler takes an additional argument that specifies the callback function
that should be called with the result.

### 3. Effects

Effects are used for effectful operations, such as IO, that happen at the edges of
the computation. The effects do not cascade. An effect can contain the following keys:

* `:id` - optional unique identifier for the event
* `:inputs` - optional set of inputs that trigger the event to run when changed
* `:outputs` - optional set of outpus that the event will produce when running the handler
* `:handler` - a function that handles the business logic for the effect

#### Incoming Effects

Effects that declare `:outputs` are used to generate the initial input to the
engine. For example, an effect that injects a timestamp can look as follows:

```clojure
{:id      :timestamp
 :outputs [:ts]
 :handler (fn [_ {:keys [ts]}]
            {:ts (.getTime (java.util.Date.))})}
```

The effect has an `:id` key specifying the unique identifier that is used be trigger the event
by calling the `domino.core/trigger-effects` function. This function accepts a collection of
event ids, e.g: `(trigger-effects ctx [:timestamp])`.

The handler accepts two arguments: a context containing the current state of the engine, and a list of output values.

#### Outgoing Effects

Effects that declare `:inputs` will be run after events have been transacted and the new context is produced. These effects are defined as a map of `:inputs` and a `:handler` function.

The handler accepts two arguments: a context containing the current state of the engine, and a list of input values.

 For example:

```clojure
{:inputs [:total]
 :handler (fn [ctx {:keys [total]}]
            (when (> total 1337)
              (println "Woah. That's a lot.")))}
```

## Usage

**1. Require `domino.core`**

<pre><code class="language-clojure lang-eval-clojure" data-external-libs="https://raw.githubusercontent.com/domino-clj/domino/master/src">
(require '[domino.core :as domino])
</code></pre>

**2. Declare your schema**

Let's take a look at a simple engine that accumulates a total. Whenever an amount is set, this value is added to the current value of the total. If the total exceeds `1337` at any point, it prints out a statement that says `"Woah. That's a lot."`

```clojure lang-eval-clojure
(def schema
  {:model   [[:amount {:id :amount}]
             [:total {:id :total}]]
   :events  [{:id      :update-total
              :inputs  [:amount]
              :outputs [:total]
              :handler (fn [ctx {:keys [amount]} {:keys [total]}]
                         {:total (+ total amount)})}]
   :effects [{:inputs [:total]
              :handler (fn [ctx {:keys [total]}]
                         (when (> total 1337)
                           (js/alert "Woah. That's a lot.")))}]})
```

This schema declaration is a map containing three keys:

* The `:model` key declares the shape of the data model used by Domino.
* The `:events` key contains pure functions that represent events that are triggered when their inputs change. The events produce updated values that are persisted in the state.
* The `:effects` key contains the functions that produce side effects based on the updated state.

Using a unified model referenced by the event functions allows us to easily tell how a particular piece of business logic is triggered.

The event engine generates a direct acyclic graph (DAG) based on the `:input` keys declared by each event that's used to compute the new state in a transaction. This approach removes any ambiguity regarding when and how business logic is executed.

Domino explicitly separates the code that modifies the state of the data from the code that causes side effects. This encourages keeping business logic pure and keeping the effects at the edges of the application.

**3. Initialize the engine**

The `schema` that we declared above provides a specification for the internal data model and the code that operates on it. Once we've created a schema, we will need to initialize the data flow engine. This is done by calling the `domino/initialize` function. This function can be called by providing a schema along with an optional initial state map. In our example, we will give it the `schema` that we defined above, and an initial value for the state with the `:total` set to `0`.

```clojure lang-eval-clojure
(def ctx (atom (domino/initialize schema {:total 0})))
```

Calling the `initialize` function creates a context `ctx` that's used as the initial state for the engine. The context will contain the model, events, effects, event graph, and db (state). In our example we use an atom in order to easily update the state of the engine.

**4. Transact your external data changes**

We can update the state of the data by calling `domino/transact` that accepts the current `ctx` along with an inputs vector, returning the updated `ctx`. The input vector is a collection of path-value pairs. For example, to set the value of `:amount` to `10`, you would pass in the following input vector `[[[:amount] 10]]`.

```clojure lang-eval-clojure
(swap! ctx domino/transact [[[:amount] 10]])
```

The updated `ctx` contains `:domino.core/change-history` key which is a simple vector of all the changes as they were applied to the data in execution order of the events that were triggered.

```clojure lang-eval-clojure
(:domino.core/change-history @ctx)
```

We can see the new context contains the updated total amount and the change history shows the order in which the changes were applied.

The `:domino.core/db` key in the context will contain the updated state reflecting the changes applied by running the events.

```clojure lang-eval-clojure
(:domino.core/db @ctx)
```

Finally, let's update the `:amount` to a value that triggers an effect.

```clojure lang-eval-clojure
(require '[reagent.core :as reagent])

(defn button []
  [:button
   {:on-click #(swap! ctx domino/transact [[[:amount] 2000]])}
   "trigger effect"])

(reagent/render-component [button] js/klipse-container)
```

### Interceptors

Domino provides the ability to add interceptors pre and post event execution. Interceptors are defined in the schema's model. If there are multiple interceptors applicable, they are composed together.

In the metadata map for a model key, you can add a `:pre` and `:post` key to define these interceptors.
Returning a `nil` value from an interceptor will short circuit execution. For example, we could check
if the context is authorized before running the events as follows:

```clojure lang-eval-clojure
(let [ctx (domino/initialize
            {:model  [[:foo {:id   :foo
                             :pre  [(fn [handler]
                                      (fn [ctx inputs outputs]
                                        ;; only run the handler if ctx contains
                                        ;; :authorized key
                                        (when (:authorized ctx)
                                          (handler ctx inputs outputs))))]
                             :post [(fn [handler]
                                      (fn [result]
                                        (handler (update result :foo #(or % -1)))))]}]]
             :events [{:inputs  [:foo]
                       :outputs [:foo]
                       :handler (fn [ctx {:keys [foo]} outputs]
                                  {:foo (inc foo)})}]})]
  (map :domino.core/db
       [(domino/transact ctx [[[:foo] 0]])
        (domino/transact (assoc ctx :authorized true) [[[:foo] 0]])]))
```

### Triggering Effects

Effects can act as inputs to the data flow engine. For example, this might happen when a button is clicked and you want a value to increment. This can be accomplished with a call to `trigger-effects`.

`trigger-effects` takes a list of effects that you would like trigger and calls `transact` with the current state of the data from all the inputs of the effects. For example:

```clojure lang-eval-clojure
(let [ctx
      (domino.core/initialize
        {:model   [[:total {:id :total}]]
         :effects [{:id      :increment-total
                    :outputs [:total]
                    :handler (fn [_ current-state]
                               (update current-state :total inc))}]}
        {:total 0})]

(:domino.core/db (domino.core/trigger-effects ctx [:increment-total])))
```

This wraps up everything you need to know to start using Domino. You can see a more detailed example using Domino with re-frame [here](https://domino-clj.github.io/demo).

## Possible Use Cases

- UI state management
- FSM
- Reactive systems / spreadsheet-like models

## Example App

* demo applications can be found [here](https://github.com/domino-clj/examples)

## Inspirations

- [re-frame](https://github.com/Day8/re-frame)
- [javelin](https://github.com/hoplon/javelin)
- [reitit](https://github.com/metosin/reitit)

## License

Copyright © 2019

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
