## Why

cypher2sql today only emits ClickHouse SQL against external tables. Local CSV
datasets cannot be explored as a property graph without a database. We need an
in-process path: load CSV into a DataFrame-like model (Tablesaw or Apache Arrow,
never Spark), map rows into the Cypher/PuppyGraph node-edge model, and evaluate
Cypher to return a subgraph.

## What Changes

- Add CSV → DataFrame loading (Tablesaw primary; Arrow as interchange option).
- Map DataFrame columns to node/edge types using existing `schema.json` mappings.
- Build an in-memory property-graph view over those tables (ids, attributes, keys).
- Evaluate a Cypher subset (reuse parser/AST) to produce a **subgraph** result
  (matched nodes + relationships), not ClickHouse SQL.
- Optional CLI mode to run Cypher over CSV inputs and print/export the subgraph.
- **Non-goals**: Spark (or any Spark API/runtime); OPTIONAL MATCH / WITH /
  variable-length paths in v1; replacing the existing SQL CTE converter;
  distributed execution; mutating Cypher (CREATE/MERGE/DELETE).

## Capabilities

### New Capabilities
- `csv-dataframe-loading`: Load CSV files into Tablesaw tables (DataFrame-like),
  with optional Arrow export/import; typed columns and basic load errors.
- `graph-dataframe-mapping`: Bind DataFrames to PuppyGraph node/edge types via
  schema mapped fields; expose graph ids, attributes, and relationship keys.
- `cypher-subgraph-execution`: Apply a supported Cypher MATCH/WHERE/RETURN
  subset against the in-memory graph model and return a subgraph (nodes + edges).

### Modified Capabilities
- `cli`: Add optional flags/mode to load CSVs, bind schema, run Cypher, and
  emit subgraph output (SQL conversion mode remains default).

## Impact

- New dependencies: Tablesaw (CSV/DataFrame); optionally Apache Arrow Java for
  columnar interchange — **no** Spark dependencies.
- New packages under `src/main/scala` (dataframe load, graph mapping, subgraph
  executor); tests with small CSV fixtures.
- Reuses `CypherParser`, AST, and `SchemaReader`; does not change SQL CTE
  semantics unless CLI wiring shares entrypoints carefully.
- Memory-bound: suitable for local/medium CSVs, not warehouse-scale data.
