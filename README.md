# domino

**WARNING: Alpha library. Subject to breaking API changes**

## Rationale

Domino is a data flow engine that helps you organize the interactions between your data model and events. Domino allows you to declare your business logic using a directed acyclic graph of events and effects. Whenever an external change is transacted to the data model, the graph determines the chain of events that will be executed, and side effects triggered as a result of the computation.

A common problem applications tend to have a lot of ad hoc business logic. This negatively affects maintainability because it becomes difficult to reason about how one change affects another part of the program. This also makes it a challenge to refactor, change, or add to programs. Domino makes the interactions between pieces of business logic explicit and centralized.

Domino explicitly separates logic that makes changes to the data model from side effectful functions. Business logic functions in Domino explicitly declare how they interact with the data model by declaring their inputs and outputs. Domino builds graphs of related events using these declarations. This approach handles cascading business logic out of the box, and provides a data specification for your business logic. 

## Usage

Domino consists of three main concepts:

1. **model**: The model represents the paths within an EDN data structure. These paths will typically represent fields within a document. Each path entry is a tuple where the first value is the path segment, and the second value is the metadata associated with it. If the path is to be used for effects and/or events, the metadata must contain the `:id` key. For example, `[:amount {:id :amount}]` is the path entry to the `:amount` key within the data model and can be referenced in your events and effects as `:amount` (defined by the `:id`). You can nest paths within each other, such as the following model definition: 

```clojure 
[[:patient [:first-name {:id :fname}]]]
```

2. **events**: The events define the business logic associated with the changes of the model. Whenever an external value is transacted, any associated events are computed. Events are defined by three keys; an `:inputs` vector, an `:outputs` vector,  and a `:handler` function. The handler accepts three arguments: a context containing the current state of the engine, a list of the input values, and a list of the output values. The function should produce a vector of outputs matching the declared `:outputs` key. For example:

```clojure
{:inputs  [:amount]
 :outputs [:total]
 :handler (fn [ctx [amount] [total]]
            [(+ total amount)])}
```

3. **effects**: Effects are executed after events have been transacted and the new context is produced. Effects are defined as a map of `:inputs` and `:handler` function, where the handler accepts two arguments: a context containing the current state of the engine, and a list of input values. The effects do not cascade. For example:

```clojure
{:inputs [:total]
 :handler (fn [ctx [total]]
            (when (> total 1337)
              (println "Woah. That's a lot.")))}
```

Steps to using Domino:

1. Require domino.core

```clojure
(require '[domino.core :as domino])
```

2. Declare your schema

Let's take a look at a simple engine that accumulates a total. Whenever an amount is set, this value is added to the current value of the total. If the total exceeds 1337 at any point, it prints out a statement that says "Woah. That's a lot."

```clojure
(def schema
  {:model   [[:amount {:id :amount}]
             [:total {:id :total}]]
   :events  [{:inputs  [:amount]
              :outputs [:total]
              :handler (fn [ctx [amount] [total]]
                         [(+ total amount)])}]
   :effects [{:inputs [:total]
              :handler (fn [ctx [total]]
                         (when (> total 1337)
                           (println "Woah. That's a lot.")))}]})
```

3. Initialize the engine

You initialize the engine by calling the `domino/initialize!` function. This function is a one- or two-arity function, taking a schema and, optionally, an initial state map. In our example, we will give it our defined schema, and an initial value for `:total` as 0.

```clojure
(def ctx (atom (domino/initialize! schema {:total 0})))
```

`initialize!` will create the initial state of the engine (context). This will contain the model, events, effects, event graph, and db (state). In our example we use an atom in order to easily update the state of the engine.

4. Transact your external data changes

We can update the state of the data by calling the `domino/transact` function that accepts the current ctx and an inputs vector, and returns the updated context. The input vector is a collection of path-value pairs. For example, to set the value of `:amount` to 10, you would pass in the following input vector `[[[:amount] 10]].

```clojure
(swap! ctx domino/transact [[[:amount] 10]])
```

The updated context contains the `:change-history` which is a simple vector of all the changes as they were applied to the data (in sequence).

```clojure
@ctx
; =>
#_{:domino.core/model {...}
   :domino.core/events [...]
   :domino.core/effects {...}
   :domino.core/db {:total 10 :amount 10}
   :domino.core/graph {...}
   :change-history [[[:amount] 10] [[:total] 10]]}
```

We can see the new context contains the updated total amount and the change history shows the order in which the changes were applied.

## Possible Use Cases

- UI state management
- FSM
- Reactive systems / spreadsheet-like models

## Example App

There is a demo front-end test page under `env/dev/cljs/domino/test_page.cljs`

## Inspirations

- [re-frame](https://github.com/Day8/re-frame)
- [javelin](https://github.com/hoplon/javelin)

## License

Copyright © 2019 

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
