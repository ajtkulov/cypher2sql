package cypher2sql

import cypher2sql.graph.GraphMaterializer
import cypher2sql.mapping.CsvGraphMapping
import cypher2sql.schema.SchemaReader
import cypher2sql.subgraph.SubgraphExecutor

import java.nio.file.{Files, Path}

/** Execute query_shared_inn_and_snils.cypher over all CSVs under data/. */
@main def runSharedInnAndSnils(): Unit =
  val root = Path.of(".")
  val schema = SchemaReader.readPath(root.resolve("schema.json")) match
    case Right(s) => s
    case Left(e) =>
      System.err.println(e); sys.exit(1)

  val mapping =
    CsvGraphMapping
      .load(root.resolve("data/mapping.all.json"))
      .flatMap(CsvGraphMapping.validateAgainstSchema(_, schema)) match
      case Right(m) => m
      case Left(e) =>
        System.err.println(e); sys.exit(1)

  println("Loading all data/*.csv into graph...")
  val t0 = System.nanoTime()
  val graph = GraphMaterializer.materialize(schema, mapping, root.resolve("data")) match
    case Right(g) => g
    case Left(e) =>
      System.err.println(e); sys.exit(1)
  println(f"Graph ready in ${(System.nanoTime() - t0) / 1e9}%.1fs")

  val cypher = Files.readString(root.resolve("data/query_shared_inn_and_snils.cypher"))
  println("Executing:")
  println(cypher.trim)
  println()

  val t1 = System.nanoTime()
  SubgraphExecutor.execute(cypher, graph) match
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)
    case Right(sg) =>
      println(f"Done in ${(System.nanoTime() - t1) / 1e9}%.1fs")
      println(s"nodes: ${sg.nodes.size}  relationships: ${sg.relationships.size}")
      println()
      println(sg.toPrettyString)
