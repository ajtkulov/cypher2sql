## Purpose

Translate a supported Cypher query AST plus `GraphSchema` (and optional
`TableSizes`) into ClickHouse-oriented SQL: a `WITH stepN AS (...)` CTE funnel
ending in a final SELECT. Each join step uses exactly two inputs.

## Requirements

### Requirement: Accept only convertible query shapes
Conversion SHALL require at least one MATCH and exactly one RETURN, and SHALL
reject WITH clauses and OPTIONAL MATCH with explicit error messages.

#### Scenario: WITH rejected
- **WHEN** the query contains a WITH clause
- **THEN** conversion returns `Left` including `WITH clauses are not supported`

#### Scenario: OPTIONAL MATCH rejected
- **WHEN** any Match has `optional = true`
- **THEN** conversion returns `Left` mentioning OPTIONAL MATCH is not supported

### Requirement: Reject unsupported relationship features
Conversion SHALL fail when a relationship lacks a type, has multiple types,
has a variable-length range, or has a property map.

#### Scenario: Variable-length relationship
- **WHEN** a relationship pattern includes a length range
- **THEN** conversion returns `Left` with `Variable-length relationships are not supported`

### Requirement: Seed filtered node-only MATCH
A node-only MATCH that introduces a new binding SHALL emit a seed CTE selecting
all node id and attribute fields as `` `alias.field` `` from the mapped table,
with WHERE predicates applied.

#### Scenario: Filtered Person seed
- **WHEN** `MATCH (p:Person) WHERE p.last_name = 'John' RETURN p.first_name, p.last_name`
- **THEN** SQL contains a step selecting from `puppy.people_agg` with `WHERE last_name = 'John'`
  and final SELECT projecting `` prev.`p.first_name` `` and `` prev.`p.last_name` ``

### Requirement: Fuse first path hop into one physical join
When the first path hop has no prior CTE and the start node is not already
CTE-bound, conversion SHALL join the start-node table and edge table in one
physical join (no seed CTE), placing the larger table first according to
`TableSizes`.

#### Scenario: Larger people_agg first
- **WHEN** `MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship) WHERE p.last_name = 'John' RETURN r`
  and people_agg has more rows than people_citizenship
- **THEN** SQL has a single join CTE with `FROM puppy.people_agg AS p INNER JOIN puppy.people_citizenship AS r`
  and the last_name filter applied in that step

#### Scenario: Larger edge table first
- **WHEN** table sizes list the edge table larger than the node table
- **THEN** FROM places the edge table first and joins the node table second

### Requirement: Join subsequent hops against previous CTE
After a CTE exists, introducing a relationship SHALL emit
`FROM <edge-or-node table> INNER JOIN <prev step> AS prev` with ON keys derived
from schema fromKey/toKey and node ids.

#### Scenario: Seed then citizenship join
- **WHEN** a filtered Person seed is followed by `MATCH (p)-[r:CITIZENSHIP]->(c:Citizenship)`
- **THEN** the next step joins `puppy.people_citizenship` to the previous step on
  `r.person_hash = prev.\`p.person_hash\``

### Requirement: Derive co-located nodes from edge tables
When a newly bound node's qualified table equals the relationship's table,
conversion SHALL project node fields from the edge alias via key/attribute
mappings and MUST NOT require a separate join to the node table.

#### Scenario: Citizenship from people_citizenship
- **WHEN** Citizenship shares `people_citizenship` with CITIZENSHIP
- **THEN** the join projects `` r.citizenship AS `c.name` `` (and id fields) without
  a second Citizenship table scan

### Requirement: Always project node IDs with attributes
For every bound node, CTE projections SHALL include the distinct union of id
field names and attribute field names so later hops can join on node IDs.

#### Scenario: Repeated edge join retains target id
- **WHEN** `MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship)<-[r1:CITIZENSHIP]-(p1:Person)`
- **THEN** the step joining `r1` uses `` r1.citizenship = prev.`c.citizenship` ``
  (the Citizenship id is present on the previous CTE, not only `c.name`)

### Requirement: Carry bound variables through CTE steps
Each subsequent CTE SHALL re-project all currently bound variables' graph fields
from `prev` and introduce newly bound aliases from the base table.

#### Scenario: Multi-MATCH chain
- **WHEN** multiple MATCH clauses extend the pattern
- **THEN** later steps still expose earlier aliases such as `p.*` columns via `prev`

### Requirement: Translate WHERE and property maps
Node property maps and WHERE expressions SHALL become SQL predicates using
schema column mappings. Node-vs-node inequality SHALL expand to id-field
comparisons.

#### Scenario: Property map on pattern node
- **WHEN** a path binds `(c:Citizenship {name: 'RU'})` in a fused first hop
- **THEN** the join WHERE includes `r.citizenship = 'RU'` (or equivalent mapped column)

#### Scenario: Node inequality
- **WHEN** WHERE contains `p1 <> p`
- **THEN** SQL compares corresponding person id columns (e.g. person_hash)

### Requirement: ClickHouse-oriented expression dialect
String/boolean/null literals, comparisons, arithmetic, and string predicates
SHALL emit ClickHouse-friendly SQL (`pow`, `startsWith`, `endsWith`,
`positionCaseInsensitive`, `match`, `XOR`, `SKIP`→`OFFSET`, parameters as `{name}`).

#### Scenario: LIMIT and OFFSET
- **WHEN** RETURN has LIMIT and SKIP
- **THEN** the final SELECT includes `LIMIT` and `OFFSET` clauses

### Requirement: Expand RETURN items
RETURN of a variable SHALL expand to all graph fields of that binding as
`` prev.`alias.field` AS `alias.field` ``. Lone `RETURN *` SHALL expand all
bindings. Mixing `*` with other items SHALL fail. Aliasing an expanded multi-column
variable SHALL fail.

#### Scenario: Return node and property
- **WHEN** `RETURN p.person_hash, c.name`
- **THEN** final SELECT projects those CTE columns only

### Requirement: Unknown labels fail clearly
Unknown node or relationship labels, missing table mappings, and unbound
variables SHALL produce `Left` errors naming the problem.

#### Scenario: Unknown node label
- **WHEN** query uses `:Unknown`
- **THEN** conversion returns `Left` including `Unknown node label`

### Requirement: RETURN DISTINCT may be ignored
Until DISTINCT support is intentionally added, conversion SHALL succeed for
`RETURN DISTINCT` queries without requiring `SELECT DISTINCT` in the emitted SQL
(the distinct flag may be ignored).

#### Scenario: DISTINCT does not fail conversion
- **WHEN** a convertible query uses `RETURN DISTINCT p`
- **THEN** conversion returns `Right(sql)` and the SQL need not contain `SELECT DISTINCT`
