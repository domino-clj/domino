### 0.1.1

- renamed `initialize!` to `initialize` since it's a pure function
- updated the model parser to handle segments without an options map
- inputs and outputs for events are now maps containing the keys
  specified in the `:inputs` and `:outputs` vectors
- event handler functions now must return a map with keys
  matching the keys specified in the `:outputs`
 - introduces `:pre` and `:post` conditions
 - triggering events via tags or ids  


