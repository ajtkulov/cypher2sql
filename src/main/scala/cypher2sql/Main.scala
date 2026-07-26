package cypher2sql

import cypher2sql.schema.{SchemaReader, TableSizeReader, TableSizes}
import cypher2sql.sql.Cypher2Sql
import org.rogach.scallop.*

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
  verify()

@main def main(args: String*): Unit =
  val conf = Conf(args)
  val schemaPath = conf.schema()

  val schema = SchemaReader.readPath(Path.of(schemaPath)).orElse(
    SchemaReader.readResource(schemaPath)
  ) match
    case Right(s)  => s
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)

  val tableSizePath = conf.tableSize()
  val tableSizes = TableSizeReader.readPath(Path.of(tableSizePath)).orElse(
    TableSizeReader.readResource(tableSizePath)
  ) match
    case Right(s) => s
    case Left(_)  => TableSizes(Nil)

  val cypher =
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

  Cypher2Sql.convert(cypher, schema, tableSizes) match
    case Right(sql) =>
      println(sql)
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)
