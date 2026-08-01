## Context

cypher2sql already parses Cypher and loads PuppyGraph `schema.json`, but
execution is SQL generation for ClickHouse only. This change adds a parallel
in-process path for local CSV datasets: DataFrame storage → schema-mapped graph
view → Cypher evaluation → subgraph.

Constraints: Scala 3 / JVM; reuse parser + schema model; no Spark of any kind;
keep the existing SQL converter intact.

## Goals / Non-Goals

**Goals:**
- Load one or more CSV files into Tablesaw `Table`s (DataFrame-like API).
- Optionally round-trip via Apache Arrow for interchange, without making Arrow
  the primary query engine.
- Map tables to node/edge types using existing `mappedField` / id / key metadata.
- Evaluate a Cypher MATCH/WHERE/RETURN subset over that mapping and return a
  subgraph (distinct matched nodes and relationships with properties).
- Expose the flow via library API and optional CLI flags.

**Non-Goals:**
- Apache Spark, Spark SQL, Dataset/DataFrame from Spark, or Spark-compatible APIs.
- Replacing or rewriting `SqlConverter`.
- Full openCypher (OPTIONAL, WITH, var-length, multi-type rels, writes).
- Out-of-core / distributed execution; warehouse-scale CSVs.
- Automatic schema inference from CSV alone (schema.json remains required).

## Decisions

### 1. Tablesaw as primary DataFrame; Arrow as optional interchange
- **Choice:** Use Tablesaw for CSV load, column access, filters, and joins.
  Provide optional Arrow `VectorSchemaRoot` / IPC export-import later.
- **Why:** Tablesaw is a small JVM DataFrame library with mature CSV support and
  join/filter ops that map cleanly to graph hops. Arrow is excellent for
  columnar interchange but heavier as the sole execution substrate.
- **Alternatives:** Arrow-only (more plumbing for CSV+joins); Polars JNI (extra
  native deps); custom row arrays (reinvent DataFrame).

### 2. Logical graph as views over tables, not a duplicated object graph
- **Choice:** `MappedGraph` holds `Table` per physical `schema.table`, plus
  accessors that project node/edge records by label using schema mappings.
- **Why:** Avoids copying every row into Node/Rel case classes up front; subgraph
  materialization happens for matched entities only.
- **Alternatives:** Eager `Map[Id, Node]` materialization (simpler, more memory);
  generate SQL against DuckDB (powerful, but another engine + not the ask).

### 3. Subgraph executor reuses AST; separate from SqlConverter
- **Choice:** New `SubgraphExecutor` walks Match hops with Tablesaw joins/filters,
  parallel to (not inside) `SqlConverter`.
- **Why:** SQL CTE funnel assumptions (ClickHouse dialect, table sizes) do not
  apply; sharing would couple unrelated failure modes.
- **Supported v1 surface:** Same convertible subset as SQL path where practical
  (multi-MATCH, single-type rels, property maps, WHERE compares, RETURN vars).
  Result type: `Subgraph(nodes, relationships)` not SQL text.

### 4. CSV binding config
- **Choice:** Explicit binding file or CLI args map `schema.table` → CSV path
  (e.g. `puppy.people_agg=/data/people.csv`). Header row required; column names
  MUST match `sourceFieldName` values in schema.
- **Why:** PuppyGraph schemas already name physical columns; inferring from
  labels alone is ambiguous when multiple labels share a table.

### 5. CLI remains SQL-default
- **Choice:** New flags such as `--mode subgraph --csv-bind …` (names TBD in
  tasks). Omitting them preserves current SQL conversion behavior.
- **Why:** Non-breaking; existing `run` scripts keep working.

## Risks / Trade-offs

- [Large CSV OOM] → Document memory limits; stream/chunk later; start with tests
  on small fixtures.
- [Tablesaw join performance vs ClickHouse] → Accept for local exploration; do
  not claim warehouse parity.
- [Semantic drift vs SqlConverter] → Share rejection rules for unsupported Cypher;
  add paired tests where both paths apply to the same fixture schema.
- [Arrow unused in v1] → Keep Arrow behind an optional module/flag so the core
  path ships with Tablesaw only if Arrow integration slips.
- [Co-located node/edge same table] → Mirror converter logic: project node attrs
  from the edge/table columns without fake duplicate scans.

## Migration Plan

1. Add dependencies and packages behind new API (no default CLI behavior change).
2. Land library + tests with CSV fixtures under `src/test/resources`.
3. Wire optional CLI mode; document examples.
4. Rollback = unused code path / flag off; SQL path unchanged.

## Open Questions

- Exact CLI flag names and subgraph output format (JSON vs CSV of nodes/edges)?
- Ship Arrow in the same milestone or Tablesaw-only first?
- Should RETURN DISTINCT dedupe subgraph entities in v1 (likely yes for subgraph)?
