## 1. Dependencies and package skeleton

- [x] 1.1 Add Tablesaw dependency to `build.sbt` (confirm no Spark transitive deps)
- [x] 1.2 Optionally add Apache Arrow Java deps behind a clear module/comment for interchange
- [x] 1.3 Create packages: `cypher2sql.dataframe`, `cypher2sql.graph`, `cypher2sql.subgraph`
- [x] 1.4 Add small CSV fixtures under `src/test/resources/csv/` matching test schema column names

## 2. CSV / DataFrame loading

- [x] 2.1 Implement CSV → Tablesaw `Table` loader with header requirement and path errors
- [x] 2.2 Add `CsvLoaderSpec` covering success and missing-file cases
- [x] 2.3 (Optional) Implement Tablesaw ↔ Arrow export/import helpers and a smoke test

## 3. Graph mapping over DataFrames

- [x] 3.1 Implement table-binding map (`schema.table` → `Table`) with missing-binding errors
- [x] 3.2 Implement node projection (id ∪ attributes via `mappedField` / fallbacks)
- [x] 3.3 Implement edge projection (id, fromKey, toKey, attributes)
- [x] 3.4 Support co-located node/edge sharing one bound table
- [x] 3.5 Add `MappedGraphSpec` using Citizenship-style co-location fixtures

## 4. Cypher subgraph execution

- [x] 4.1 Define `Subgraph` / node / relationship result types (label/type + properties)
- [x] 4.2 Implement `SubgraphExecutor` for node-only MATCH + WHERE + RETURN
- [x] 4.3 Implement single-hop and multi-MATCH path joins using schema keys
- [x] 4.4 Reject WITH, OPTIONAL MATCH, var-length, multi-type rels, rel property maps
- [x] 4.5 Deduplicate nodes/relationships in the returned subgraph
- [x] 4.6 Add `SubgraphExecutorSpec` for filter, hop, rejection, and offline CSV cases

## 5. CLI wiring

- [x] 5.1 Add optional subgraph mode flags (CSV bindings + mode) without changing SQL default
- [x] 5.2 Wire schema + bindings + Cypher → subgraph print/export; non-zero exit on errors
- [x] 5.3 Add a CLI-oriented test or documented example invocation for subgraph mode

## 6. Validation

- [x] 6.1 Run full `sbt test` and fix regressions
- [x] 6.2 Confirm assembly still builds and SQL-only `run` path is unchanged
