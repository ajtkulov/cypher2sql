## Context

We already have Tablesaw CSV loading, `MappedGraph` (physical `schema.table` →
table), and `SubgraphExecutor` for Cypher→subgraph. Files under `/data/*.csv`
are **result exports** (often TabSeparatedWithNames) with columns like
`p.person_hash`, `i.code`, `p1.last_name`—not PuppyGraph physical table layouts.
Users will supply an explicit mapping from those columns into graph entities.

## Goals / Non-Goals

**Goals:**
- Inspect data-directory CSVs (list, delimiter, headers, counts, samples).
- Define and load a JSON mapping: file → nodes/relationships → column bindings.
- Materialize a `MappedGraph` (or equivalent entity store) from mapped files and
  run existing Cypher subgraph queries.
- CLI entrypoints for inspect and mapping-driven query.

**Non-Goals:**
- Spark / distributed execution.
- Inferring mapping automatically from filenames alone.
- Changing ClickHouse SQL conversion.
- Full openCypher beyond the current subgraph subset.

## Decisions

### 1. Mapping JSON (provided by user)
- **Choice:** A `mapping.json` lists files (relative to `--data-dir`), delimiter
  hint, and `nodes` / `relationships` with `label`/`type`, `id` column map, and
  optional property column maps. Relationship entries declare `from`/`to` as
  references to node role ids in the same file (or shared id columns).
- **Why:** Result CSVs encode several entities per row; physical-table binds
  cannot express that.
- **Alternatives:** Only `--csv-bind` (insufficient); infer from `alias.field`
  headers (fragile without user confirmation).

### 2. Materialization strategy
- **Choice:** For each mapped file, project unique nodes by id into per-label
  Tablesaw tables (union across files), and build edge tables from relationship
  mappings (from/to key columns). Then bind those tables into `MappedGraph`
  using synthetic qualified names or schema table names when they match.
- **Why:** Reuses `SubgraphExecutor` without a second query engine.
- **Alternatives:** Row-oriented object graph only (harder joins); DuckDB (extra
  engine).

### 3. Inspect is read-only and mapping-agnostic
- **Choice:** Inspect walks `*.csv` / `*.tsv`, sniffs delimiter (`\t` vs `,`),
  reports headers and `n` sample rows—no schema.json required.
- **Why:** Users need to author mappings from real headers.

### 4. CLI modes
- **Choice:** `--mode inspect --data-dir data` and
  `--mode subgraph --data-dir data --mapping mapping.json --cypher q.cypher`
  (existing `--csv-bind` remains). Default remains `sql`.
- **Why:** Non-breaking; clear separation of inspect vs query.

### 5. Delimiter
- **Choice:** Auto-detect for inspect; mapping may override per file
  (`delimiter: "\t"`). Loader extended to honor delimiter (current CsvLoader
  assumes comma).
- **Why:** `/data` files are TSV-style.

## Risks / Trade-offs

- [Wide result CSVs duplicate entities] → Dedupe nodes by id when projecting.
- [Mapping vs schema.json drift] → Validate labels/types against schema when
  schema is provided; clear errors on unknown labels.
- [Large wiki.snils.csv] → Document memory limits; stream later if needed.
- [Relationship not in schema] → Allow mapping-defined edge types only if schema
  has them, or fail fast (prefer schema-required for v1).

## Migration Plan

1. Ship inspect + mapping loader + materializer behind new CLI flags.
2. Add example `data/mapping.example.json` for inn/snils exports.
3. No change to default SQL path; rollback = unused flags.

## Open Questions

- Should one mapping cover many globbed files (`*.inn.csv`) with one template?
- Output format for inspect: human text vs JSON?
