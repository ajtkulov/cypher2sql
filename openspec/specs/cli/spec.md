## Purpose

Provide a command-line entrypoint that loads schema (and optional table sizes),
reads Cypher from a file or a built-in sample, and either prints SQL (default),
inspects data-directory CSVs, or runs in-memory subgraph mode over CSV bindings
or a user-provided CSV↔graph mapping.

## Requirements

### Requirement: Schema argument with default
The CLI SHALL accept `--schema` defaulting to `schema.json`, resolving a
filesystem path first and falling back to a classpath resource.

#### Scenario: Missing schema exits
- **WHEN** neither path nor resource yields a schema
- **THEN** an error is printed to stderr and the process exits with code 1

### Requirement: Table-size argument is optional soft-fail
The CLI SHALL accept `--table-size` defaulting to `table_size.json`. Load failure
SHALL NOT exit; conversion continues with empty sizes.

#### Scenario: Missing table-size file
- **WHEN** table-size path/resource cannot be read
- **THEN** conversion still runs using `TableSizes(Nil)`

### Requirement: Cypher from file or sample
When `--cypher <path>` is provided, the CLI SHALL read that file (missing file →
exit 1). When omitted, the CLI SHALL convert a built-in sample MATCH/RETURN query.

#### Scenario: Cypher file not found
- **WHEN** `--cypher` points to a non-existent file
- **THEN** stderr reports the missing path and exit code is 1

### Requirement: Print SQL or conversion error
On successful conversion the CLI SHALL print the SQL string to stdout. On
conversion failure it SHALL print the error to stderr and exit with code 1.

#### Scenario: Successful conversion
- **WHEN** schema and Cypher are valid and convertible
- **THEN** stdout contains a `WITH` CTE funnel SQL statement and exit code is 0

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

### Requirement: Inspect mode for data directory CSVs
The CLI SHALL provide an inspect mode that summarizes CSV/TSV files under
`--data-dir` (defaulting to `data` when appropriate) without requiring Cypher
or a mapping file.

#### Scenario: Inspect data
- **WHEN** the CLI is invoked with inspect mode and `--data-dir data`
- **THEN** stdout reports each file’s headers, delimiter, and row count and exit
  code is 0 on success

### Requirement: Mapping-driven subgraph query mode
The CLI SHALL accept a `--mapping` path together with subgraph mode (and
`--data-dir` / `--schema` / `--cypher`) to load mapped CSVs into the graph and
run Cypher, without breaking default SQL conversion or existing `--csv-bind`
subgraph usage.

#### Scenario: Query with mapping
- **WHEN** subgraph mode is run with a valid mapping, schema, data dir, and
  Cypher file
- **THEN** the process emits a subgraph result and exits 0 on success

#### Scenario: Mapping file missing
- **WHEN** mapping-driven subgraph mode is selected but the mapping path is
  missing or unreadable
- **THEN** stderr reports the error and exit code is 1
