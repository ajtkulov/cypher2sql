## ADDED Requirements

### Requirement: Materialize MappedGraph from CSV mapping
The system SHALL project nodes and relationships from mapped CSV files into
bound Tablesaw tables (deduplicating nodes by id) and expose them through
`MappedGraph` (or an equivalent bind API) so Cypher subgraph execution can run.

#### Scenario: Multi-entity result row
- **WHEN** a result CSV row contains columns for `p`, `i`, and `p1` per mapping
- **THEN** materialization yields distinct Person and INN nodes (and declared
  relationships) without requiring physical `puppy.*` table CSVs

#### Scenario: Union across multiple files
- **WHEN** mapping lists several CSV files that contribute the same node label
- **THEN** nodes are merged by id into one logical table for that label

### Requirement: Support non-comma delimiters for mapped loads
When a mapping or inspect path specifies a tab (or other) delimiter, CSV loading
for that file SHALL use that delimiter.

#### Scenario: Load TSV data file
- **WHEN** a mapped file is tab-separated
- **THEN** columns align to the header names used in the mapping
