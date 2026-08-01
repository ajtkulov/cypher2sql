package cypher2sql.graph

import cypher2sql.schema.*
import tech.tablesaw.api.Table

/**
 * In-memory property graph as views over bound Tablesaw tables.
 * Keys are qualified table names (`schema.table`).
 */
final class MappedGraph(
    val schema: GraphSchema,
    private val tables: Map[String, Table]
):
  def table(qualified: String): Option[Table] = tables.get(qualified)

  def requireTable(qualified: String): Either[String, Table] =
    tables.get(qualified).toRight(s"Missing CSV binding for table: $qualified")

  def nodeTable(label: String): Either[String, Table] =
    for
      nt <- schema.node(label).toRight(s"Unknown node label: $label")
      ds <- nt.table.toRight(s"Node label '$label' has no table mapping")
      t  <- requireTable(ds.qualifiedTable)
    yield t

  def edgeTable(label: String): Either[String, (EdgeType, Table)] =
    for
      et <- schema.edge(label).toRight(s"Unknown relationship type: $label")
      ds <- et.table.toRight(s"Relationship '$label' has no table mapping")
      t  <- requireTable(ds.qualifiedTable)
    yield (et, t)

  /** Physical column for a node graph field. */
  def nodeSourceCol(nodeType: NodeType, graphField: String): Either[String, String] =
    val ds = nodeType.table.getOrElse:
      return Left(s"Node '${nodeType.label}' has no table mapping")
    ds.sourceFor(graphField)
      .orElse(Option.when(nodeType.attributes.exists(_.name == graphField))(graphField))
      .orElse(Option.when(nodeType.id.exists(_.name == graphField))(graphField))
      .toRight(s"Unknown property '$graphField' on node '${nodeType.label}'")

  /** Physical column for an edge graph field. */
  def edgeSourceCol(edgeType: EdgeType, graphField: String): Either[String, String] =
    val ds = edgeType.table.getOrElse:
      return Left(s"Relationship '${edgeType.label}' has no table mapping")
    ds.sourceFor(graphField)
      .orElse(Option.when(edgeType.attributes.exists(_.name == graphField))(graphField))
      .orElse(
        Option.when(
          edgeType.id.exists(_.name == graphField) ||
            edgeType.fromKey.exists(_.name == graphField) ||
            edgeType.toKey.exists(_.name == graphField)
        )(graphField)
      )
      .toRight(s"Unknown property '$graphField' on relationship '${edgeType.label}'")

  def nodeGraphFields(nodeType: NodeType): List[String] =
    (nodeType.id ++ nodeType.attributes).map(_.name).distinct

  def edgeGraphFields(edgeType: EdgeType): List[String] =
    (edgeType.id ++ edgeType.fromKey ++ edgeType.toKey ++ edgeType.attributes)
      .map(_.name)
      .distinct

object MappedGraph:
  def bind(
      schema: GraphSchema,
      bindings: Map[String, Table]
  ): Either[String, MappedGraph] =
    Right(MappedGraph(schema, bindings))

  /** Load bindings from `qualifiedTable -> csvPath`. */
  def bindCsvPaths(
      schema: GraphSchema,
      csvPaths: Map[String, String]
  ): Either[String, MappedGraph] =
    import cypher2sql.dataframe.CsvLoader
    val loaded = csvPaths.toList.foldLeft[Either[String, Map[String, Table]]](Right(Map.empty)):
      case (Left(e), _) => Left(e)
      case (Right(acc), (qualified, path)) =>
        CsvLoader.load(path).map(t => acc + (qualified -> t))
    loaded.map(MappedGraph(schema, _))
