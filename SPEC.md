## General concepts

In many cases changes to a particular field in the data model often require changes to other fields as well.

We need a method to represent relationships in the business logic, and to explicitly declare how different business rules relate to one another.

Datagrid manages relationship in business logic by requiring that the input and the output fields are explicitly declared.

Knowing the field relationships allows inferring what parts of the business logic should be triggered whenever a particular value changes in the data model.

The business rules can now be run transactionally against the current state of the model to produce a change set for the next state.


By declaring

## Architectural concepts

Datagrid is a monorepo with several sub-projects.

- datagrid-core: The event engine and API to hook into implementations (e.g. front-end or back-end)
- datagrid-ui: Reagent?
- datagrid-memory: Engine to run in memory -- can be cljc to support execution on both front or back? Perhaps fits with core? TBD
- datagrid-ws:
- datagrid-ajax:

(maybe others?)

## Components

### Model
```clojure
[[:title {:validation ..}]
 [:user {:id :user}
  [:first-name {:id :fname}]
  [:last-name {:id :lname}]]]
```

#### Reactive Atom

With cljs we piggyback off of reagent's ratom. For the back end, we have to implement our own.

### event engine

The event engine requires a model definition

```clojure
:model
{:path-id {:path       [:path :to :field]
           :spec       integer? ;optional type for coercion and validation
           :validation [:required]}}
```

TODO: change type to :spec, so can perhaps be a data spec? This permits using it for collections

Events can be one of three types:
* effectful events used to send outputs to IO, e.g: changing state of UI widgets
* data events that transform the state of the model
* collection events for manipulating arbitrary data collections

```clojure
[{:inputs  [:path1 :path2]
  :outputs [:path3]
  :type    :data
  :handler (fn [context [path1 path2] [path3]]
             ["value for path 3"])}]
```

#### Using the event engine
A changeset is given as an input, events that take nodes in the changeset as inputs are then triggered, returning an expanded changeset.

 kg 10, lb 10
 lb 10, kg 10

 [kg->lb, lb->kg]
   Run kg->lb
   kg 10, lb 22
   Run lb->kg
   NO CHANGE

  [lb->kg, kg->lb]
   Run lb->kg
   kg 4.? lb 10
   Run kg->lb
   no change

### Managing collection values

TODO: how are collections stored

TODO: talk about some challenges/edge cases/limitations

#### Collection events

There are three types of collection events:

- `:col/add`
- `:col/remove`
- `:col/move`

If using collection events, a `:spec` is required in your path definition.

TODO: talk about how addition works given spec. Perhaps some way to define a default item to insert??

TODO: Remove event

TODO: Move event

An example definition of valid collection events for a given path:

```clojure
{:type     :collection
 :inputs   [:path]
 :outputs  [:path]
 :actions  [:col/add :col/remove :col/move]}
```

#### Modifying collection children

### Processing events

When the value of a path in the model's map is changed, the event engine executes.

1. Generate the dependency graph of events that have that path as an input
2. Sequentially (TODO: synchronously or not? Presumably synchronously due to nature of dependencies) execute the `handler` for each event

If a no op / no change to any values then no events and effects are triggered.

#### Dependency graph

The dependency graph is generated from inputs and outputs. If an event has an output path that is used in inputs for other events, it is meant to propagate, and so the graph continues.

However, if an event's handler is already executed once in a single engine execution, it is skipped to prevent dependency recursion.

It's "kind of" like a DAG, but not really due to one-directional nature and custom handling for dependency resolution.

### event sources

#### local events in-memory

#### remote events

* Ajax
* WS
* Protocol

#### API



### reactive components

#### API

### UI library

Changes in the UI should always reflect the state of the data in the application. Therefore, any change in the UI can be inferred from the changes in the underlying data.

When changes in the DOM are driven by changes in the data we know precisely what nodes need to be virtual. I agree that everything Svelte does can be done using a macro. Basically, you can generate the DOM nodes in the browser, and keep references to the ones that are associated with the data elements. Whenever the data changes you repaint the nodes with updated values.

#### API

##
