package cypher2sql

import cypher2sql.dataframe.{CsvInspect, CsvLoader}
import cypher2sql.graph.{GraphMaterializer, MappedGraph}
import cypher2sql.mapping.CsvGraphMapping
import cypher2sql.schema.{SchemaReader, TableSizeReader, TableSizes}
import cypher2sql.sql.Cypher2Sql
import cypher2sql.subgraph.SubgraphExecutor
import org.rogach.scallop.*
import tech.tablesaw.api.Table

import java.nio.file.{Files, Path}

class Conf(arguments: Seq[String]) extends ScallopConf(arguments):
  val schema = opt[String](
    name = "schema",
    descr = "Path to schema JSON (file or classpath resource)",
    default = Some("schema.json")
  )
  val tableSize = opt[String](
    name = "table-size",
    descr = "Path to table_size.json (file or classpath resource)",
    default = Some("table_size.json")
  )
  val cypher = opt[Path](
    name = "cypher",
    descr = "Path to Cypher query file",
    required = false
  )
  val mode = opt[String](
    name = "mode",
    descr = "Execution mode: sql (default), subgraph, or inspect",
    default = Some("sql")
  )
  val csvBind = opt[List[String]](
    name = "csv-bind",
    descr =
      "Bind qualified table to CSV (repeatable): --csv-bind schema.table=/path.csv",
    default = Some(Nil)
  )
  val dataDir = opt[Path](
    name = "data-dir",
    descr = "Directory of CSV/TSV files (inspect / mapping modes)",
    default = Some(Path.of("data"))
  )
  val mapping = opt[Path](
    name = "mapping",
    descr = "CSV↔graph mapping JSON (subgraph mode with result CSVs)",
    required = false
  )
  verify()

@main def main(args: String*): Unit =
  val conf = Conf(args)

  conf.mode().toLowerCase match
    case "inspect" =>
      runInspect(conf.dataDir())
    case "sql" =>
      val schema = loadSchema(conf.schema())
      runSql(schema, conf.tableSize(), readCypher(conf))
    case "subgraph" =>
      val schema = loadSchema(conf.schema())
      conf.mapping.toOption match
        case Some(mappingPath) =>
          runSubgraphMapped(schema, conf.dataDir(), mappingPath, readCypher(conf))
        case None =>
          runSubgraphBinds(schema, conf.csvBind(), readCypher(conf))
    case other =>
      System.err.println(s"Unknown mode: $other (expected sql, subgraph, or inspect)")
      sys.exit(1)

private def loadSchema(schemaPath: String) =
  SchemaReader.readPath(Path.of(schemaPath)).orElse(SchemaReader.readResource(schemaPath)) match
    case Right(s)  => s
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)

private def readCypher(conf: Conf): String =
  conf.cypher.toOption match
    case Some(path) =>
      if !Files.isRegularFile(path) then
        System.err.println(s"Cypher file not found: $path")
        sys.exit(1)
      Files.readString(path)
    case None =>
      """
        MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship)
        WHERE p.last_name = 'John'
        RETURN p, c.name
        LIMIT 100
      """

private def runInspect(dataDir: Path): Unit =
  CsvInspect.inspectDir(dataDir) match
    case Right(summaries) =>
      print(CsvInspect.formatReport(summaries))
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)

private def runSql(
    schema: cypher2sql.schema.GraphSchema,
    tableSizePath: String,
    cypherText: String
): Unit =
  val tableSizes = TableSizeReader.readPath(Path.of(tableSizePath)).orElse(
    TableSizeReader.readResource(tableSizePath)
  ) match
    case Right(s) => s
    case Left(_)  => TableSizes(Nil)

  Cypher2Sql.convert(cypherText, schema, tableSizes) match
    case Right(sql) =>
      println(sql)
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)

private def runSubgraphBinds(
    schema: cypher2sql.schema.GraphSchema,
    binds: List[String],
    cypherText: String
): Unit =
  val entries = binds.flatMap: raw =>
    raw.split(";", -1).toList.map(_.trim).filter(_.nonEmpty)

  val parsed = entries.foldLeft[Either[String, Map[String, String]]](Right(Map.empty)):
    case (Left(e), _) => Left(e)
    case (Right(acc), raw) =>
      raw.split("=", 2) match
        case Array(qualified, path) if qualified.nonEmpty && path.nonEmpty =>
          Right(acc + (qualified.trim -> path.trim))
        case _ =>
          Left(s"Invalid --csv-bind '$raw' (expected schema.table=/path/file.csv)")

  val pathMap = parsed match
    case Right(m) => m
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)

  if pathMap.isEmpty then
    System.err.println(
      "subgraph mode requires --mapping <file> or at least one --csv-bind schema.table=/path.csv"
    )
    sys.exit(1)

  val tables: Map[String, Table] =
    pathMap.toList.foldLeft(Map.empty[String, Table]):
      case (acc, (qualified, path)) =>
        CsvLoader.load(path) match
          case Right(t) => acc + (qualified -> t)
          case Left(err) =>
            System.err.println(err)
            sys.exit(1)

  val graph = MappedGraph.bind(schema, tables) match
    case Right(g) => g
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)

  runExecutor(cypherText, graph)

private def runSubgraphMapped(
    schema: cypher2sql.schema.GraphSchema,
    dataDir: Path,
    mappingPath: Path,
    cypherText: String
): Unit =
  val mapping = CsvGraphMapping.load(mappingPath).flatMap(CsvGraphMapping.validateAgainstSchema(_, schema)) match
    case Right(m) => m
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)

  val graph = GraphMaterializer.materialize(schema, mapping, dataDir) match
    case Right(g) => g
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)

  runExecutor(cypherText, graph)

private def runExecutor(cypherText: String, graph: MappedGraph): Unit =
  SubgraphExecutor.execute(cypherText, graph) match
    case Right(sg) =>
      println(sg.toPrettyString)
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)
