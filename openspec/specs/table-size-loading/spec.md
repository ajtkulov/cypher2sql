## Purpose

Load optional table row-count metadata used to order the first physical join
(larger table in FROM). Missing or empty sizes must not block conversion.

## Requirements

### Requirement: Parse table_size.json entries
The system SHALL parse a JSON array of `{schema, table_name, rows}` into
`TableSizes` with lookup by schema+table and by bare table name.

#### Scenario: Schema-qualified lookup
- **WHEN** an entry has schema `puppy`, table `people_agg`, rows `215484744`
- **THEN** `tableSizes.rows("puppy", "people_agg")` returns `Some(215484744)`

#### Scenario: Bare table fallback
- **WHEN** looking up a table by name alone after loading sizes
- **THEN** `tableSizes.rows("people_agg")` returns the matching row count if present

### Requirement: Unknown tables count as zero
When a table has no size entry, row count used for ordering SHALL be treated as
0 (smaller than any known positive size).

#### Scenario: Missing size entry
- **WHEN** conversion joins tables without size metadata
- **THEN** missing tables are ordered as if they have 0 rows

### Requirement: Soft-fail on CLI load
When the CLI cannot load `--table-size`, conversion SHALL continue with empty
`TableSizes(Nil)` rather than exiting.

#### Scenario: Absent table-size file
- **WHEN** `--table-size` path and resource both fail
- **THEN** the process continues conversion with no size hints
