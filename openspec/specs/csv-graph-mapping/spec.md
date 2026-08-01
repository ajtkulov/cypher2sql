## Purpose

Load a user-provided JSON mapping that binds CSV/TSV columns to graph node
labels and relationship types for materialization into an in-memory graph.

## Requirements

### Requirement: Load user-provided mapping document
The system SHALL load a JSON mapping file that declares, for one or more CSV
files, how columns map to graph node labels and relationship types (including
id and property column bindings).

#### Scenario: Valid mapping loads
- **WHEN** a well-formed mapping.json references existing files under the data
  directory
- **THEN** the loader returns a structured mapping object ready for materialization

#### Scenario: Invalid mapping JSON
- **WHEN** the mapping file is not valid JSON or missing required fields
- **THEN** the loader returns a clear error describing the problem

### Requirement: Map CSV columns to node identities and properties
For each node mapping entry, the system SHALL bind CSV column names to graph id
fields and optional property fields for a node label.

#### Scenario: Person from p.* columns
- **WHEN** mapping assigns Person ids from `p.person_hash` and properties from
  `p.first_name`, `p.last_name`, etc.
- **THEN** materialization produces Person nodes with those property values

### Requirement: Map CSV columns to relationships
For each relationship mapping entry, the system SHALL bind from/to endpoint
columns (and optional relationship properties) to a relationship type.

#### Scenario: HAS_INN from person and inn columns
- **WHEN** mapping declares HAS_INN from Person id column to INN id column on
  the same row
- **THEN** materialization produces HAS_INN relationships connecting those nodes

### Requirement: Validate labels against schema when schema is provided
When a GraphSchema is supplied, the mapping loader SHALL reject unknown node
labels or relationship types that are not present in the schema.

#### Scenario: Unknown label rejected
- **WHEN** mapping references label `Foo` and schema has no such node
- **THEN** load fails with an error naming `Foo`
