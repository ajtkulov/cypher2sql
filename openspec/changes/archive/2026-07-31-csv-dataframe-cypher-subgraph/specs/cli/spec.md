## ADDED Requirements

### Requirement: Optional subgraph CLI mode
The CLI SHALL provide an optional mode that loads schema, binds CSV files to
physical tables, runs Cypher, and emits a subgraph result, without changing the
default SQL-conversion behavior when that mode is not selected.

#### Scenario: Default remains SQL conversion
- **WHEN** the CLI is invoked with `--schema` and `--cypher` and without
  subgraph/CSV mode flags
- **THEN** behavior remains Cypher→SQL conversion to stdout as today

#### Scenario: Subgraph mode with CSV bindings
- **WHEN** subgraph mode is selected with valid schema, CSV bindings, and Cypher
- **THEN** the process prints or writes a subgraph representation and exits 0 on
  success

### Requirement: Subgraph mode errors exit non-zero
On missing CSV bindings, load failures, or unsupported/failed Cypher execution
in subgraph mode, the CLI SHALL print an error to stderr and exit with code 1.

#### Scenario: Unbound table in subgraph mode
- **WHEN** subgraph mode is selected but a required table has no CSV binding
- **THEN** stderr explains the missing binding and exit code is 1
