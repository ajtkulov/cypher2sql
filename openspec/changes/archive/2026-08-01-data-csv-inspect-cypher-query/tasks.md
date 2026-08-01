## 1. Inspect data CSVs

- [x] 1.1 Implement data-dir scanner for `*.csv` / `*.tsv` with missing-dir errors
- [x] 1.2 Detect delimiter (tab vs comma), read headers, count rows, collect samples
- [x] 1.3 Add `CsvInspectSpec` using fixtures modeled on `/data` TSV headers
- [x] 1.4 Wire CLI `--mode inspect --data-dir <path>` and document output format

## 2. Mapping format and loader

- [x] 2.1 Define JSON mapping schema (files, delimiter, nodes, relationships, column maps)
- [x] 2.2 Implement mapping loader with clear JSON/required-field errors
- [x] 2.3 Validate node labels and relationship types against `GraphSchema` when provided
- [x] 2.4 Add example `data/mapping.example.json` for inn/snils-style exports
- [x] 2.5 Add `CsvGraphMappingSpec` for valid load, invalid JSON, unknown label

## 3. Materialize graph from mapped CSVs

- [x] 3.1 Extend CSV loader to honor per-file delimiter (tab-separated `/data` files)
- [x] 3.2 Project unique nodes by id into per-label tables from mapped columns
- [x] 3.3 Build relationship tables from from/to column bindings
- [x] 3.4 Union nodes/edges across multiple mapped files; bind into `MappedGraph`
- [x] 3.5 Add materialization tests for multi-entity rows and multi-file merge

## 4. Cypher query path

- [x] 4.1 Run `SubgraphExecutor` against mapping-materialized `MappedGraph`
- [x] 4.2 CLI: `--mode subgraph --mapping … --data-dir … --schema … --cypher …`
- [x] 4.3 Preserve default SQL mode and existing `--csv-bind` subgraph path
- [x] 4.4 End-to-end test: inspect headers → mapping → Cypher over sample data CSV

## 5. Validation

- [x] 5.1 Run `sbt test` and fix regressions
- [x] 5.2 Smoke-test inspect and mapping query against `/data` with the example mapping
