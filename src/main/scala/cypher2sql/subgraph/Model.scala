package cypher2sql.subgraph

/** Distinct nodes and relationships produced by Cypher evaluation. */
final case class Subgraph(
    nodes: List[GraphNode],
    relationships: List[GraphRelationship]
):
  def toPrettyString: String =
    val nb = new StringBuilder
    nb.append("nodes:\n")
    nodes.foreach: n =>
      nb.append(s"  (:${n.label} ${propsStr(n.properties)})\n")
    nb.append("relationships:\n")
    relationships.foreach: r =>
      nb.append(
        s"  [${r.idKey}] -[:${r.relType}]-> ${propsStr(r.properties)} " +
          s"from=${propsStr(r.fromKeys)} to=${propsStr(r.toKeys)}\n"
      )
    nb.toString

  private def propsStr(p: Map[String, String]): String =
    p.toList.sortBy(_._1).map((k, v) => s"$k:'$v'").mkString("{", ", ", "}")

final case class GraphNode(
    label: String,
    /** Stable identity from schema id fields. */
    idKey: String,
    properties: Map[String, String]
)

final case class GraphRelationship(
    relType: String,
    idKey: String,
    fromKeys: Map[String, String],
    toKeys: Map[String, String],
    properties: Map[String, String]
)
