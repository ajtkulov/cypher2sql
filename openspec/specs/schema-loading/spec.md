## Purpose

Load PuppyGraph-shaped `schema.json` into an in-memory `GraphSchema` used for
label lookup, table qualification, and source↔target field mapping during SQL
conversion.

## Requirements

### Requirement: Load node and edge types by label
The system SHALL parse `node[]` and `edge[]` into `NodeType` / `EdgeType` values
and expose lookup by label via `GraphSchema.node` / `GraphSchema.edge`.

#### Scenario: Known Person label
- **WHEN** a valid schema containing label `Person` is loaded
- **THEN** `schema.node("Person")` returns that node type with id and attributes

#### Scenario: Known relationship label
- **WHEN** a valid schema containing edge label `HAS_INN` is loaded
- **THEN** `schema.edge("HAS_INN")` returns from/to labels, keys, and data source

### Requirement: External data source and qualified table
Each node/edge type SHALL carry an optional `ExternalDataSource` with catalog,
schema, table, enabled flag, and mapped fields. The converter-facing qualified
table SHALL be `schema.table` (catalog is loaded but unused at convert time).

#### Scenario: Qualified table name
- **WHEN** a data source has schema `puppy` and table `people_agg`
- **THEN** `qualifiedTable` is `puppy.people_agg`

### Requirement: Mapped fields resolve graph names to SQL columns
`ExternalDataSource.sourceFor(graphField)` SHALL return the `sourceFieldName`
for a matching `targetFieldName`, supporting multiple targets mapped from one
physical column.

#### Scenario: Dual mapping for Citizenship
- **WHEN** mapped fields map source `citizenship` to both `citizenship` and `name`
- **THEN** `sourceFor("name")` and `sourceFor("citizenship")` both return `Some("citizenship")`

#### Scenario: Edge key mapping
- **WHEN** edge key target `puppy_from_person_hash` maps from `person_hash`
- **THEN** `sourceFor("puppy_from_person_hash")` returns `Some("person_hash")`

### Requirement: Load from path or resource
`SchemaReader` SHALL support reading from a filesystem path and from a classpath
resource, returning `Left` with an error message on failure.

#### Scenario: Missing schema
- **WHEN** neither path nor resource can be read
- **THEN** result is `Left` describing the failure

### Requirement: Catalog metadata is loadable
The system SHALL parse optional `catalog[]` entries (name, type, jdbc) into the
model even if SQL conversion does not use them.

#### Scenario: Catalog entry present
- **WHEN** schema JSON includes a catalog named `clickhouse_data`
- **THEN** `GraphSchema` includes that catalog entry after load
