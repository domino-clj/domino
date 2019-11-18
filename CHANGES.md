### 0.2.1

- async event support

### 0.2.0

- **[BREAKING]** renamed `initialize!` to `initialize` since it's a pure function
- **[BREAKING]** inputs and outputs for events are now maps containing the keys
  specified in the `:inputs` and `:outputs` vectors
- **[BREAKING]** event handler functions now must return a map with keys
  matching the keys specified in the `:outputs`
- updated the model parser to handle segments without an options map
- introduces `:pre` and `:post` conditions
- `trigger-events` fn added to domino.core, allowing triggering events via ids
- add schema definition validation
- validate for duplicate ids in model
