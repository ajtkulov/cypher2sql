package cypher2sql

import cypher2sql.schema.SchemaReader
import cypher2sql.sql.Cypher2Sql

import java.nio.file.Path

@main def main(args: String*): Unit =
  val (schemaPath, cypherArgs) =
    args.toList match
      case "--schema" :: path :: rest => (path, rest)
      case rest                       => ("schema.json", rest)

  val schema = SchemaReader.readPath(Path.of(schemaPath)).orElse(
    SchemaReader.readResource(schemaPath)
  ) match
    case Right(s)  => s
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)

  val cypher =
    if cypherArgs.nonEmpty then cypherArgs.mkString(" ")
    else
      """
        MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship)
        WHERE p.last_name = 'John'
        RETURN p, c.name
        LIMIT 100
      """

  Cypher2Sql.convert(cypher, schema) match
    case Right(sql) =>
      println(sql)
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)
