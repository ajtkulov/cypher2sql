package cypher2sql.mapping

import cypher2sql.schema.GraphSchema
import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.parser.decode

import java.nio.file.{Files, Path}

/** User-provided CSV → graph mapping. */
final case class CsvGraphMapping(files: List[MappedFile])

final case class MappedFile(
    path: String,
    delimiter: Option[String],
    nodes: List[MappedNode],
    relationships: List[MappedRelationship]
):
  def delimiterChar: Char =
    delimiter.match
      case Some("\\t") | Some("\t") | Some("TAB") | Some("tab") => '\t'
      case Some(s) if s.nonEmpty => s.head
      case _ => ','

final case class MappedNode(
    id: String,
    label: String,
    /** graphField -> CSV column name */
    columns: Map[String, String]
)

final case class MappedRelationship(
    `type`: String,
    from: String,
    to: String
)

object CsvGraphMapping:
  given Decoder[MappedNode] = deriveDecoder
  given Decoder[MappedRelationship] = deriveDecoder
  given Decoder[MappedFile] = deriveDecoder
  given Decoder[CsvGraphMapping] = deriveDecoder

  def load(path: Path): Either[String, CsvGraphMapping] =
    if !Files.isRegularFile(path) then Left(s"Mapping file not found: $path")
    else
      val text = Files.readString(path)
      decode[CsvGraphMapping](text).left.map(e => s"Invalid mapping JSON: ${e.getMessage}")
        .flatMap(validateStructure)

  def load(path: String): Either[String, CsvGraphMapping] =
    load(Path.of(path))

  def validateAgainstSchema(
      mapping: CsvGraphMapping,
      schema: GraphSchema
  ): Either[String, CsvGraphMapping] =
    val errors = mapping.files.flatMap: f =>
      f.nodes.flatMap: n =>
        if schema.node(n.label).isEmpty then List(s"Unknown node label: ${n.label}")
        else Nil
      ++ f.relationships.flatMap: r =>
        if schema.edge(r.`type`).isEmpty then List(s"Unknown relationship type: ${r.`type`}")
        else Nil
    if errors.isEmpty then Right(mapping) else Left(errors.mkString("; "))

  private def validateStructure(m: CsvGraphMapping): Either[String, CsvGraphMapping] =
    if m.files.isEmpty then Left("Mapping must contain at least one file entry")
    else
      m.files.foreach: f =>
        if f.path.isEmpty then return Left("Mapped file path must be non-empty")
        f.nodes.foreach: n =>
          if n.id.isEmpty || n.label.isEmpty then
            return Left(s"Node mapping in ${f.path} requires id and label")
          if n.columns.isEmpty then
            return Left(s"Node '${n.id}' in ${f.path} requires columns")
        f.relationships.foreach: r =>
          if r.`type`.isEmpty then return Left(s"Relationship in ${f.path} requires type")
          val nodeIds = f.nodes.map(_.id).toSet
          if !nodeIds.contains(r.from) then
            return Left(s"Relationship ${r.`type`} from='${r.from}' not in nodes of ${f.path}")
          if !nodeIds.contains(r.to) then
            return Left(s"Relationship ${r.`type`} to='${r.to}' not in nodes of ${f.path}")
      Right(m)
