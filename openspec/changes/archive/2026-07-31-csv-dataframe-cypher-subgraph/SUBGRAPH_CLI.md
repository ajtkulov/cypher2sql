# Subgraph mode (CSV → Tablesaw → Cypher subgraph)

Default CLI mode remains SQL conversion. Subgraph mode runs Cypher in-process
over CSV files bound to schema tables (no Spark, no database).

```bash
sbt assembly

java -jar target/scala-3.3.6/cypher2sql-assembly-0.1.0-SNAPSHOT.jar \
  --mode subgraph \
  --schema src/test/resources/schema.json \
  --csv-bind puppy.people_agg=src/test/resources/csv/people_agg.csv \
  --csv-bind puppy.people_citizenship=src/test/resources/csv/people_citizenship.csv \
  --cypher query.cypher
```

Multiple bindings may also be semicolon-separated in one `--csv-bind` value.
