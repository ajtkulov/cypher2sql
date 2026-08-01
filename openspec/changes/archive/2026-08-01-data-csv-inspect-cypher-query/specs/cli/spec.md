## ADDED Requirements

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
