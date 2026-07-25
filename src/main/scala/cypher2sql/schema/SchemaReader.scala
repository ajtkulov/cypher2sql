package cypher2sql.schema

import io.circe.*
import io.circe.parser.decode

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object SchemaReader:

  def readString(json: String): Either[String, GraphSchema] =
    decode[GraphSchema](json).left.map(_.getMessage)

  def readPath(path: Path): Either[String, GraphSchema] =
    try
      readString(Files.readString(path, StandardCharsets.UTF_8))
    catch
      case e: Exception => Left(s"Failed to read schema from $path: ${e.getMessage}")

  def readFile(path: String): Either[String, GraphSchema] =
    readPath(Path.of(path))

  def readResource(
      name: String,
      classLoader: ClassLoader = getClass.getClassLoader
  ): Either[String, GraphSchema] =
    Option(classLoader.getResourceAsStream(name)) match
      case None => Left(s"Schema resource not found: $name")
      case Some(in) =>
        try
          readString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
        catch
          case e: Exception =>
            Left(s"Failed to read schema resource $name: ${e.getMessage}")
        finally
          in.close()

  // --- Circe decoders (PuppyGraph JSON field names) ---------------------------

  private given Decoder[GraphField] = Decoder.instance: c =>
    for
      name <- c.get[String]("name")
      typeName <- c.get[String]("type")
    yield GraphField(name, typeName)

  private given Decoder[MappedField] = Decoder.instance: c =>
    for
      source <- c.get[String]("sourceFieldName")
      target <- c.get[String]("targetFieldName")
    yield MappedField(source, target)

  private def optional[A: Decoder](c: HCursor, name: String): Decoder.Result[Option[A]] =
    val field = c.downField(name)
    if field.failed then Right(None)
    else field.as[A].map(Some(_))

  private given Decoder[JdbcConfig] = Decoder.instance: c =>
    for
      username <- optional[String](c, "username")
      password <- optional[String](c, "password")
      jdbcUri <- optional[String](c, "jdbcUri")
      enableMetaCache <- optional[String](c, "enableMetaCache")
      metaCacheExpireSec <- optional[String](c, "metaCacheExpireSec")
    yield JdbcConfig(username, password, jdbcUri, enableMetaCache, metaCacheExpireSec)

  private given Decoder[Catalog] = Decoder.instance: c =>
    for
      name <- c.get[String]("name")
      typeName <- c.get[String]("type")
      jdbc <- optional[JdbcConfig](c, "jdbc")
    yield Catalog(name, typeName, jdbc)

  private given Decoder[ExternalDataSource] = Decoder.instance: c =>
    for
      enabled <- c.get[Boolean]("enabled")
      catalog <- c.get[String]("catalog")
      schema <- c.get[String]("schema")
      table <- c.get[String]("table")
      mapped <- c.getOrElse[List[MappedField]]("mappedField")(Nil)
    yield ExternalDataSource(enabled, catalog, schema, table, mapped)

  private given Decoder[DataSourceGroup] = Decoder.instance: c =>
    optional[ExternalDataSource](c, "externalDataSource").map(DataSourceGroup.apply)

  private given Decoder[NodeType] = Decoder.instance: c =>
    for
      label <- c.get[String]("label")
      id <- c.get[List[GraphField]]("id")
      attributes <- c.getOrElse[List[GraphField]]("attribute")(Nil)
      dataSource <- c.get[DataSourceGroup]("dataSourceGroup")
    yield NodeType(label, id, attributes, dataSource)

  private given Decoder[EdgeType] = Decoder.instance: c =>
    for
      label <- c.get[String]("label")
      fromNodeLabel <- c.get[String]("fromNodeLabel")
      toNodeLabel <- c.get[String]("toNodeLabel")
      id <- c.get[List[GraphField]]("id")
      fromKey <- c.get[List[GraphField]]("fromKey")
      toKey <- c.get[List[GraphField]]("toKey")
      attributes <- c.getOrElse[List[GraphField]]("attribute")(Nil)
      dataSource <- c.get[DataSourceGroup]("dataSourceGroup")
    yield EdgeType(
      label,
      fromNodeLabel,
      toNodeLabel,
      id,
      fromKey,
      toKey,
      attributes,
      dataSource
    )

  private given Decoder[GraphSchema] = Decoder.instance: c =>
    for
      catalogs <- c.getOrElse[List[Catalog]]("catalog")(Nil)
      nodes <- c.getOrElse[List[NodeType]]("node")(Nil)
      edges <- c.getOrElse[List[EdgeType]]("edge")(Nil)
    yield GraphSchema(catalogs, nodes, edges)
