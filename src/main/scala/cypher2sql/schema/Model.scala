package cypher2sql.schema

/** PuppyGraph schema: Cypher labels mapped to SQL tables. */
final case class GraphSchema(
    catalogs: List[Catalog],
    nodes: List[NodeType],
    edges: List[EdgeType]
):
  private val nodesByLabel: Map[String, NodeType] =
    nodes.map(n => n.label -> n).toMap

  private val edgesByLabel: Map[String, EdgeType] =
    edges.map(e => e.label -> e).toMap

  def node(label: String): Option[NodeType] = nodesByLabel.get(label)
  def edge(label: String): Option[EdgeType] = edgesByLabel.get(label)

  def requireNode(label: String): NodeType =
    node(label).getOrElse(
      throw IllegalArgumentException(s"Unknown node label: $label")
    )

  def requireEdge(label: String): EdgeType =
    edge(label).getOrElse(
      throw IllegalArgumentException(s"Unknown edge label: $label")
    )

final case class Catalog(
    name: String,
    typeName: String,
    jdbc: Option[JdbcConfig]
)

final case class JdbcConfig(
    username: Option[String],
    password: Option[String],
    jdbcUri: Option[String],
    enableMetaCache: Option[String],
    metaCacheExpireSec: Option[String]
)

final case class GraphField(name: String, typeName: String)

final case class MappedField(sourceFieldName: String, targetFieldName: String)

final case class ExternalDataSource(
    enabled: Boolean,
    catalog: String,
    schema: String,
    table: String,
    mappedFields: List[MappedField]
):
  /** SQL-qualified table name as `schema.table`. */
  def qualifiedTable: String = s"$schema.$table"

  /** Full catalog path `catalog.schema.table`. */
  def catalogPath: String = s"$catalog.$schema.$table"

  def sourceFor(targetFieldName: String): Option[String] =
    mappedFields.find(_.targetFieldName == targetFieldName).map(_.sourceFieldName)

final case class DataSourceGroup(external: Option[ExternalDataSource]):
  def table: Option[ExternalDataSource] = external

final case class NodeType(
    label: String,
    id: List[GraphField],
    attributes: List[GraphField],
    dataSource: DataSourceGroup
):
  def table: Option[ExternalDataSource] = dataSource.external

final case class EdgeType(
    label: String,
    fromNodeLabel: String,
    toNodeLabel: String,
    id: List[GraphField],
    fromKey: List[GraphField],
    toKey: List[GraphField],
    attributes: List[GraphField],
    dataSource: DataSourceGroup
):
  def table: Option[ExternalDataSource] = dataSource.external
