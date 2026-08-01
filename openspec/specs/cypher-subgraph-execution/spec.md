## Purpose

Evaluate a Cypher MATCH/WHERE/RETURN subset against a schema-mapped in-memory
graph and return a subgraph of matched nodes and relationships.

## Requirements

### Requirement: Execute Cypher subset over mapped graph
The system SHALL evaluate queries with at least one MATCH and exactly one RETURN
against a schema-mapped in-memory graph, reusing the existing Cypher parser/AST,
and SHALL reject the same unsupported constructs as the SQL path (WITH,
OPTIONAL MATCH, variable-length relationships, multi-type relationships,
relationship property maps) with explicit errors.

#### Scenario: Simple node filter
- **WHEN** `MATCH (p:Person) WHERE p.last_name = 'John' RETURN p` runs on a
  bound Person table containing matching rows
- **THEN** the result subgraph includes those Person nodes with their properties

#### Scenario: WITH rejected
- **WHEN** the query contains a WITH clause
- **THEN** execution returns an error stating WITH is not supported

### Requirement: Path MATCH produces relationship-connected subgraph
Multi-hop and multi-MATCH path patterns SHALL join endpoint keys per schema and
include matched relationships in the subgraph.

#### Scenario: Person to Citizenship hop
- **WHEN** `MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship) WHERE p.last_name = 'John' RETURN p, r, c`
  runs on bound tables
- **THEN** the subgraph contains the matched Person nodes, CITIZENSHIP
  relationships, and Citizenship nodes for rows that satisfy the join and filter

### Requirement: Subgraph result shape
Execution SHALL return a subgraph value containing the distinct nodes and
relationships referenced by successful matches (at least those appearing in
RETURN when variables are returned), each with label/type and property map.

#### Scenario: Distinct entities
- **WHEN** multiple match rows refer to the same node id
- **THEN** the subgraph includes that node once

### Requirement: Independent of SqlConverter output
Subgraph execution MUST produce an in-memory subgraph result and MUST NOT
require generating ClickHouse SQL or contacting an external database.

#### Scenario: Offline run
- **WHEN** only local CSV bindings and schema.json are available
- **THEN** a supported Cypher query can still produce a subgraph
