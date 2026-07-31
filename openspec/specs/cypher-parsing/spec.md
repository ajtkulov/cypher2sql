## Purpose

Parse a Cypher subset into the project AST. Parsing is permissive relative to
SQL conversion: constructs may appear on the AST and be rejected later by
`SqlConverter`.

## Requirements

### Requirement: Parse MATCH RETURN queries
The system SHALL parse queries composed of MATCH (and optionally WITH / RETURN)
clauses into a `Query` AST when the input matches the supported grammar.

#### Scenario: Simple MATCH RETURN
- **WHEN** input is `MATCH (p:Person) WHERE p.last_name = 'John' RETURN p.first_name`
- **THEN** `CypherParser.parse` returns `Right(Query)` with a Match and a Return clause

#### Scenario: Multi-MATCH with relationship chain
- **WHEN** input contains multiple MATCH clauses and a path with relationships
- **THEN** each Match is present on `Query.clauses` and path elements are `PathPattern` nodes

### Requirement: OPTIONAL MATCH and WITH are parseable
The parser SHALL accept `OPTIONAL MATCH` and `WITH` into the AST even though
conversion may reject them.

#### Scenario: OPTIONAL MATCH flag
- **WHEN** input contains `OPTIONAL MATCH (n:Person)`
- **THEN** the Match node has `optional = true`

#### Scenario: WITH clause present
- **WHEN** input contains a WITH clause before RETURN
- **THEN** the AST includes a `With` clause (conversion may still fail later)

### Requirement: Patterns, directions, and relationship lengths parse
The parser SHALL accept node labels, property maps, outgoing/incoming/undirected
relationships, relationship types (including `|`), and variable-length ranges.

#### Scenario: Undirected and variable-length
- **WHEN** input uses undirected `-[]-` or length forms such as `*`, `*2`, `*1..3`
- **THEN** parse succeeds and the RelationshipPattern records direction and length

### Requirement: Expressions and RETURN modifiers parse
The parser SHALL accept common expression operators, property access, functions,
lists/maps/parameters, and RETURN/WITH modifiers including DISTINCT, ORDER BY,
SKIP, and LIMIT.

#### Scenario: ORDER BY LIMIT
- **WHEN** RETURN includes `ORDER BY p.last_name DESC LIMIT 10`
- **THEN** the Return AST contains corresponding `orderBy` and `limit` expressions

### Requirement: Clear parse errors
On invalid syntax the parser SHALL return `Left` with a message that includes
line and column information.

#### Scenario: Invalid input
- **WHEN** input is not valid Cypher under the grammar
- **THEN** result is `Left` containing `Parse error at line` and column context

### Requirement: Unsupported write/procedure clauses are out of scope
The parser MUST NOT be relied on to accept CREATE, MERGE, DELETE, SET, UNWIND,
CALL, UNION, FOREACH, REMOVE, list/pattern comprehensions, or existential
subqueries; such inputs SHALL produce a parse error rather than a successful AST.

#### Scenario: CREATE is not accepted
- **WHEN** input is `CREATE (n:Person) RETURN n`
- **THEN** `CypherParser.parse` returns `Left` (parse error)
