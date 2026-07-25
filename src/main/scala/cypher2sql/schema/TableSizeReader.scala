package cypher2sql.schema

import io.circe.*
import io.circe.parser.decode

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object TableSizeReader:

  def readString(json: String): Either[String, TableSizes] =
    decode[List[TableSizeEntry]](json)
      .map(TableSizes.apply)
      .left
      .map(_.getMessage)

  def readPath(path: Path): Either[String, TableSizes] =
    try
      readString(Files.readString(path, StandardCharsets.UTF_8))
    catch
      case e: Exception => Left(s"Failed to read table sizes from $path: ${e.getMessage}")

  def readFile(path: String): Either[String, TableSizes] =
    readPath(Path.of(path))

  def readResource(
      name: String,
      classLoader: ClassLoader = getClass.getClassLoader
  ): Either[String, TableSizes] =
    Option(classLoader.getResourceAsStream(name)) match
      case None => Left(s"Table size resource not found: $name")
      case Some(in) =>
        try
          readString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
        catch
          case e: Exception =>
            Left(s"Failed to read table size resource $name: ${e.getMessage}")
        finally
          in.close()

  private given Decoder[TableSizeEntry] = Decoder.instance: c =>
    for
      schema <- c.get[String]("schema")
      tableName <- c.get[String]("table_name")
      rows <- c.get[Long]("rows")
    yield TableSizeEntry(schema, tableName, rows)
