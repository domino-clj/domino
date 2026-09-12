### Unreleased

- fix (#24): inputs whose db value is `nil` are now omitted from the handler input map so destructuring defaults (`:or {...}`) are applied; nil inputs were previously passed as explicit `nil`, defeating the defaults

### 0.5.0

- **[BREAKING]** an event handler may only write the ids it declared in `:outputs`; returning any other id fails the transaction with `:domino.events/undeclared-outputs`. An undeclared write was invisible to the graph — nothing downstream of that path was triggered and the value landed anyway — and was reachable by accident through a `:post` interceptor, which is composed onto every event that *reads* the path it hangs on
- an event now runs at most once per transaction for a given set of input values. The traversal queues an event once per changed path, so a transaction changing several of one event's inputs ran the handler once for each; a handler folding into its own output accumulated once per changed input
- interceptors are documented as being collected from an event's `:inputs`

### 0.3.3

- **[BREAKING]** replaced async callback API (`:async? true` + 4-arg handler with callback) with derefable returns; async handlers now return `delay`/`future`/`promise` and the engine auto-derefs
- on the JVM, `future` and `promise` block until resolved; in ClojureScript, `delay` is supported for lazy computation — truly async operations should use effects
- the `:async?` flag is silently ignored for backward compatibility

### 0.3.2

- - **[BREAKING]** namespaced `:change-history` key as `:domino.core/change-history`

### 0.3.1

- added `domino.core/event` macro helper for declaring events

### 0.3.0
- fix: trigger effect with nested model
- `trigger-events` removed, `trigger-effects`  should be used instead
- events will now be executed by the `initialize` function when initial state is provided

### 0.2.1
- async event support
- `trigger-effects` fn added to `domino.core` allowing triggering of effects via ids

### 0.2.0
- **[BREAKING]** renamed `initialize!` to `initialize` since it's a pure function
- **[BREAKING]** inputs and outputs for events are now maps containing the keys
  specified in the `:inputs` and `:outputs` vectors
- **[BREAKING]** event handler functions now must return a map with keys
  matching the keys specified in the `:outputs`
- updated the model parser to handle segments without an options map
- introduces `:pre` and `:post` conditions
- `trigger-events` fn added to `domino.core`, allowing triggering events via ids
- add schema definition validation
- validate for duplicate ids in model
