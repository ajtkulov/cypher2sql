## Purpose

Provide a command-line entrypoint that loads schema (and optional table sizes),
reads Cypher from a file or a built-in sample, and prints SQL to stdout or errors
to stderr with a non-zero exit.

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
