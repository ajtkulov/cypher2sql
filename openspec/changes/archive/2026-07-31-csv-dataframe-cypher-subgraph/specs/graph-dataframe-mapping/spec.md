## ADDED Requirements

### Requirement: Bind CSV tables to schema physical tables
The system SHALL accept an explicit binding from each required
`ExternalDataSource` qualified table (`schema.table`) to a loaded DataFrame
(Tablesaw table).

#### Scenario: Bind people_agg
- **WHEN** schema defines `puppy.people_agg` for Person and the caller binds that
  name to a loaded CSV table
- **THEN** the mapped graph can resolve Person rows from that table

#### Scenario: Missing binding for required label
- **WHEN** a Cypher query uses a label whose table is not bound
- **THEN** mapping/execution returns an error naming the missing table binding

### Requirement: Project node fields via mappedField
For a node type, the system SHALL expose graph fields (id ∪ attributes) by
reading `sourceFieldName` columns from the bound table for each
`targetFieldName`.

#### Scenario: Person properties
- **WHEN** Person maps `last_name` → source `last_name`
- **THEN** a Person record’s `last_name` graph property equals the table column
  value for that row

### Requirement: Project relationship keys from edge tables
For an edge type, the system SHALL expose fromKey/toKey/id/attribute graph
fields from the bound edge table using the same mappedField rules as SQL
conversion.

#### Scenario: HAS_INN keys
- **WHEN** HAS_INN maps `puppy_from_person_hash` ← `person_hash` and
  `puppy_to_innCode` ← `innCode`
- **THEN** relationship endpoints resolve using those physical columns

### Requirement: Co-located node and edge share one table
When a node type and an edge type share the same qualified table, the system
SHALL allow both to be satisfied by a single bound DataFrame.

#### Scenario: Citizenship co-located
- **WHEN** Citizenship and CITIZENSHIP both use `puppy.people_citizenship`
- **THEN** one CSV binding for that table supplies both node and edge projections
