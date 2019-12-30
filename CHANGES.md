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
