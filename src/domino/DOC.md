# Domino 0.4 Forensic Documentation

## API Functions

### pre-initialize

- takes a schema without an initial db.

- Must be synchronous
- Any data aside from `::db-initializers` should be serializable
- Should provide as much data about relationships/events as possible

### initialize

- Takes a schema and an initial-db and returns a domino context which supports the full API

### transact

- Takes a domino context and vector of changes. attempts to apply changes to context, following all constraints and events and recomputing relevant reactions.
- Returns the new context with zero or more triggered output effects, or throws
- Should have 2 convenience wrappers
  - One that catches the error and returns the old ctx with an error report
  - One that automatically triggers the output effects

### trigger-effects

- Takes a domino context and a sequence of triggers

- calls any input effect functions specified by triggers to update the context


### select

- gets a value or reaction by id from a domino context

### get-path

- gets the path in the structured DB for a given id

### get-in-db

- select, but only for values

### get-parents

- validate beh'r

### get-downstream

- get all ids which might be affected by id (verify)



### get-upstream

- get all ids which might affect id (verify)

## Features

### ids

An id points to a value or subcontext inside the domino context.
It can be a vector or a keyword.
Vectors allow specification of subcontext and collection element.

### reactions

A view/computation on some parent (root data such as DB, or another reaction)

Special cases:
  - events: produce a set of reactions for whether or not to run, as well as a changeset to apply.
  - constraints: a resolver or an error to signify an invalid state.

### changes

A vector-based declaration of a change to apply to the context.
Supports extension on top of basic operations.

#### Base types

`[:domino.core/set-value <id> <v>]`
: Overwrites the value at `<id>` with `<v>`

`[:domino.core/remove-value <id>]`
: removes the value at id


`[:domino.core/remove-child <id>]`
: removes an element from a collection subcontext

`[:domino.core/update-child <id> & changes]`
: performs a transaction on the specified subcontext with the passed changes

#### Included extensions

- translate `[::set-value <id> nil]` to `[::remove-value <id>]`
- translate `[:set-value [<id> <idx?> & <ids>] <v>]` to `[::update-child [<id> <idx?>] [::set-value <ids> <v>]]`
- `::set` type that allows for many changes to be represented as a nested map.

#### Writing custom extensions

- Provide your own parse functions in a map under the `::custom-change-fns` key of the domino context.
- These fns should return a valid changeset to be subsequently parsed.
- NOTE: no helpers provided for this as of yet.


### Transact Walkthrough

1. Clear any outdated metadata that may be present on the context from a previous transaction.
Currently clearing `[::transaction-report ::db-hashes ::triggered-effects]`

2. If uninitialized, run `add-events-to-queue` to ensure that initial-db triggers appropriate events.

3. Register the initial DB state

4. Enter tx-step iteration with changeset

  1. tx-step->handle-changes!->resolve-changes

      1. parse changes and then call `resolve-changes-impl`

      2. If an error is encountered, `on-fail` passed fn will be called with an error report. this bubbles up through subcontexts.

      3. Otherwise, use the changed db to compute updated reactions, and specifically triggered events.

      4. run `append-events-to-queue` which will get the value of the `::events` reaction.
         1. It will filter the ignored events (e.g. ignoring trigger, or `:once` evaluation strategy.) and add the rest to the `::event-queue`

      5. Next, we record the db-hash to prevent infinite cycles.

      6. Finally return to the `tx-step` callback.

  2. tx-step callback will:

      1. Check for any conflicts

      2. rollback if there are any unresolvable conflicts

      3. return if there are no events or conflicts

      4. attempt to run any resolvers (calling tx-step to apply resolution changes.)

      5. attempt to run any events


### Subcontexts

#### `:create-element`

A function on a subcontext collection map which will return a new domino context to be inserted as a child.

### Scratch

1. apply changeset
2. resolve conflicts or throw
3. queue triggered events
4. consume queue recursively
5. accumulate triggered effects
