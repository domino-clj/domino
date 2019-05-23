## General concepts

Changes in the UI should always reflect the state of the data in the application. Therefore, any change in the UI can be inferred from the changes in the underlying data.

In many cases changes to a particular field in the data model often require changes to other fields as well.

We need a method to represent relationships in the business logic, and to explicitly declare how different business rules relate to one another.

Datagrid manages relationship in business logic by requiring that the input and the output fields are explicitly declared.

Knowing the field relationships allows inferring what parts of the business logic should be triggered whenever a particular value changes in the data model.

The business rules can now be run transactionally against the current state of the model to produce a change set for the next state.


By declaring

## Components

### event engine

The event engine requires a model definition

```clojure
:model
{:path-id {:model/path [:path :to :field]
           :type :integer ;optional type for coercion and validation
           :validation [:required]}}
```

The events have two categories:
* effectful events used to send outputs to IO, e.g: changing state of UI widgets
* data events that transform the state of the model

```clojure
[{:inputs [:path1 :path2]
  :outputs [:path3]
  :handler (fn [context [path1 path2] [path3]]
             ["value for path 3"])}]
```

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

When changes in the DOM are driven by changes in the data we know precisely what nodes need to be virtual. I agree that everything Svelte does can be done using a macro. Basically, you can generate the DOM nodes in the browser, and keep references to the ones that are associated with the data elements. Whenever the data changes you repaint the nodes with updated values.

#### API

##
