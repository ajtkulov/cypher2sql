# Inspect + mapping-driven Cypher over `/data`

## Inspect

```bash
java -jar target/scala-3.3.6/cypher2sql-assembly-0.1.0-SNAPSHOT.jar \
  --mode inspect --data-dir data
```

Reports delimiter, headers, row counts, and sample rows for each `*.csv` / `*.tsv`.

## Query with mapping

Edit `data/mapping.example.json` (or copy) so file paths match the CSVs you want.
Then:

```bash
java -jar target/scala-3.3.6/cypher2sql-assembly-0.1.0-SNAPSHOT.jar \
  --mode subgraph \
  --schema schema.json \
  --data-dir data \
  --mapping data/mapping.example.json \
  --cypher query.cypher
```

Default `--mode sql` and `--csv-bind` subgraph binds remain unchanged.
