## Why

Local `/data/*.csv` files (query-result exports: tab-separated columns like
`p.person_hash`, `i.innCode`) need to be inspected, loaded into a property-graph
model via an **explicit mapping**, and queried with Cypher—without ClickHouse or
Spark. Core subgraph pieces exist; what is missing is CSV inspection and a
mapping format for these wide result files (not only `schema.table` physical
binds).

## What Changes

- Add **inspect** for `/data/*.csv` (and configurable dirs): list files, delimiter
  detection, headers, row counts, sample rows.
- Add a **provided CSV↔graph mapping** document (JSON) that declares how CSV
  columns become nodes/relationships (labels, id/attrs, endpoints).
- Load mapped CSVs into the in-memory graph (reuse Tablesaw + subgraph executor).
- CLI: `inspect` mode for data CSVs; subgraph/query mode that takes `--mapping`
  (and optional `--data-dir`) instead of only per-table `--csv-bind`.
- **Non-goals**: Spark; auto-inferring schema from CSV without a mapping;
  rewriting SQL CTE conversion; mutating Cypher; warehouse-scale out-of-core.

## Capabilities

### New Capabilities
- `csv-inspect`: Discover and summarize CSV/TSV files under a data directory
  (headers, delimiter, row count, samples, basic errors).
- `csv-graph-mapping`: Load a user-provided mapping that binds CSV files/columns
  to graph node labels and relationship types (ids, properties, endpoints).

### Modified Capabilities
- `cli`: Add inspect command/mode and mapping-driven load+Cypher query over
  `/data` (keep default SQL conversion and existing `--csv-bind` subgraph path).
- `graph-dataframe-mapping`: Extend binding so a mapping file can populate the
  mapped graph from result-shaped CSVs (in addition to physical table binds).
- `cypher-subgraph-execution`: No surface change required if mapping produces a
  compatible `MappedGraph`; only note multi-file merge if needed in design.

## Impact

- New mapping JSON schema + loader; inspect utilities over `data/`.
- Builds on `CsvLoader`, `MappedGraph`, `SubgraphExecutor`.
- Example mappings for `*.inn.csv` / `*.snils.csv` under `data/`.
- Memory-bound local exploration of result CSVs as a queryable graph.
