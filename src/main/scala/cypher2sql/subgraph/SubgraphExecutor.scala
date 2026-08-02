package cypher2sql.subgraph

import cypher2sql.ast.*
import cypher2sql.graph.MappedGraph
import cypher2sql.parser.CypherParser
import cypher2sql.schema.*
import tech.tablesaw.api.Table

import scala.jdk.CollectionConverters.*

private enum EntityVal:
  case Node(label: String, props: Map[String, String])
  case Rel(
      relType: String,
      props: Map[String, String],
      fromKeys: Map[String, String],
      toKeys: Map[String, String]
  )

private type Row = Map[String, EntityVal]

object SubgraphExecutor:
  def execute(
      cypher: String,
      graph: MappedGraph
  ): Either[String, Subgraph] =
    CypherParser.parse(cypher).flatMap(execute(_, graph))

  def execute(query: Query, graph: MappedGraph): Either[String, Subgraph] =
    try Right(new SubgraphExecutor(graph).run(query))
    catch case e: ExecError => Left(e.msg)

private final class ExecError(val msg: String) extends RuntimeException(msg)

private final class SubgraphExecutor(graph: MappedGraph):
  private var anon = 0

  /** Cached node snapshots + id → node index per label. */
  private val nodeCache =
    scala.collection.mutable.Map.empty[String, (List[EntityVal.Node], Map[String, EntityVal.Node])]

  /** Cached edge rows + indexes by from-key / to-key physical values. */
  private val edgeCache =
    scala.collection.mutable.Map.empty[String, EdgeIndex]

  private final case class EdgeIndex(
      rows: List[Map[String, String]],
      byFrom: Map[String, List[Map[String, String]]],
      byTo: Map[String, List[Map[String, String]]],
      fromSrc: List[String],
      toSrc: List[String]
  )

  private def fail(msg: String): Nothing = throw ExecError(msg)

  private def nodesFor(label: String): (List[EntityVal.Node], Map[String, EntityVal.Node]) =
    nodeCache.getOrElseUpdate(
      label, {
        val nt = graph.schema.node(label).getOrElse(fail(s"Unknown node label: $label"))
        val table = graph.nodeTable(label).fold(fail, identity)
        val nodes = scanNodes(nt, table)
        val idFields = nt.id.map(_.name)
        val byId = nodes.map(n => idFields.map(f => n.props.getOrElse(f, "")).mkString("\u0000") -> n).toMap
        (nodes, byId)
      }
    )

  private def edgesFor(edgeType: EdgeType): EdgeIndex =
    edgeCache.getOrElseUpdate(
      edgeType.label, {
        val (_, edgeTable) = graph.edgeTable(edgeType.label).fold(fail, identity)
        val fromSrc =
          edgeType.fromKey.map(f => graph.edgeSourceCol(edgeType, f.name).fold(fail, identity))
        val toSrc =
          edgeType.toKey.map(f => graph.edgeSourceCol(edgeType, f.name).fold(fail, identity))
        val rows = snapshotTable(edgeTable)
        def key(row: Map[String, String], cols: List[String]): String =
          cols.map(c => row.getOrElse(c, "")).mkString("\u0000")
        val byFrom = rows.groupBy(r => key(r, fromSrc))
        val byTo = rows.groupBy(r => key(r, toSrc))
        EdgeIndex(rows, byFrom, byTo, fromSrc, toSrc)
      }
    )

  def run(query: Query): Subgraph =
    val matches = query.clauses.collect { case m: Match => m }
    val withs = query.clauses.collect { case w: With => w }
    val returns = query.clauses.collect { case r: Return => r }

    if withs.nonEmpty then fail("WITH clauses are not supported")
    if returns.size != 1 then fail("Query must contain exactly one RETURN clause")
    if matches.isEmpty then fail("Query must contain at least one MATCH clause")

    var rows: List[Row] = List(Map.empty)
    matches.foreach: m =>
      rows = processMatch(m, rows)

    materialize(rows, returns.head)

  private def processMatch(m: Match, rows: List[Row]): List[Row] =
    if m.optional then fail("OPTIONAL MATCH is not supported in subgraph mode yet")
    val parts = m.pattern.parts.map(_.element)
    parts match
      case (n: NodePattern) :: Nil =>
        processNodeOnly(n, m.where, rows)
      case other =>
        other.foldLeft(rows): (acc, el) =>
          el match
            case n: NodePattern =>
              processNodeOnly(n, None, acc)
            case PathPattern(start, chain) =>
              processPath(start, chain, m.where, acc)

  private def processNodeOnly(
      node: NodePattern,
      where: Option[Expr],
      rows: List[Row]
  ): List[Row] =
    val alias = node.variable.getOrElse(fresh("_n"))
    if rows.exists(_.contains(alias)) && node.variable.isDefined then
      // Already bound: filter existing rows
      rows.filter: row =>
        row.get(alias) match
          case Some(EntityVal.Node(label, props)) =>
            node.labels.forall(_ == label) &&
              propsMatch(props, node.properties) &&
              where.forall(eval(_, row))
          case _ => false
    else
      val label =
        node.labels.headOption.getOrElse(fail(s"Node variable '$alias' requires a label"))
      if node.labels.size > 1 then fail(s"Multiple labels not supported: ${node.labels}")
      val nt = graph.schema.node(label).getOrElse(fail(s"Unknown node label: $label"))
      val nodes = nodesFor(label)._1.filter: n =>
        propsMatch(n.props, node.properties)

      if rows == List(Map.empty) || rows.forall(_.isEmpty) then
        nodes.flatMap: n =>
          val row: Row = Map(alias -> n)
          if where.forall(eval(_, row)) then Some(row) else None
      else
        rows.flatMap: prev =>
          nodes.flatMap: n =>
            val row = prev + (alias -> n)
            if where.forall(eval(_, row)) then Some(row) else None

  private def processPath(
      start: NodePattern,
      chain: List[(RelationshipPattern, NodePattern)],
      where: Option[Expr],
      rows: List[Row]
  ): List[Row] =
    var current = processNodeOnly(start, None, rows)
    chain.zipWithIndex.foreach { case ((rel, right), idx) =>
      val isLast = idx == chain.length - 1
      current = processHop(rel, right, if isLast then where else None, current)
    }
    current

  private def processHop(
      rel: RelationshipPattern,
      right: NodePattern,
      where: Option[Expr],
      rows: List[Row]
  ): List[Row] =
    if rel.length.nonEmpty then fail("Variable-length relationships are not supported")
    if rel.types.isEmpty then fail("Relationship type is required")
    if rel.types.size > 1 then fail(s"Multiple relationship types not supported: ${rel.types}")
    rel.properties.foreach(_ => fail("Relationship property maps are not supported yet"))

    val relAlias = rel.variable.getOrElse(fresh("_r"))
    val rightAlias = right.variable.getOrElse(fresh("_n"))
    val edgeType =
      graph.schema.edge(rel.types.head).getOrElse(fail(s"Unknown relationship type: ${rel.types.head}"))

    val leftAlias = inferLeftAlias(rows, edgeType, rel.direction)
    val outgoing = resolveOutgoing(rel.direction, edgeType, leftAlias, rows, right.labels.headOption)

    val rightExpected = if outgoing then edgeType.toNodeLabel else edgeType.fromNodeLabel
    if right.labels.nonEmpty && !right.labels.contains(rightExpected) then
      fail(s"Expected label $rightExpected for '$rightAlias', got ${right.labels}")

    val rightNodeType =
      graph.schema
        .node(right.labels.headOption.getOrElse(rightExpected))
        .getOrElse(fail(s"Unknown node label: $rightExpected"))

    val fromKeyFields = edgeType.fromKey.map(_.name)
    val toKeyFields = edgeType.toKey.map(_.name)
    val idx = edgesFor(edgeType)

    rows.flatMap: prev =>
      val left = prev.getOrElse(
        leftAlias,
        fail(s"Unbound variable: $leftAlias")
      ) match
        case n: EntityVal.Node => n
        case _                 => fail(s"Variable '$leftAlias' is not a node")

      val leftIdFields =
        if outgoing then graph.schema.requireNode(edgeType.fromNodeLabel).id.map(_.name)
        else graph.schema.requireNode(edgeType.toNodeLabel).id.map(_.name)
      val leftIdKey = leftIdFields.map(f => left.props.getOrElse(f, "")).mkString("\u0000")
      val candidates =
        if outgoing then idx.byFrom.getOrElse(leftIdKey, Nil)
        else idx.byTo.getOrElse(leftIdKey, Nil)

      candidates.flatMap: erow =>
        val relProps = graph.edgeGraphFields(edgeType).flatMap { gf =>
          graph.edgeSourceCol(edgeType, gf).toOption.map(src => gf -> erow.getOrElse(src, ""))
        }.toMap
        val fromProps = fromKeyFields.zip(idx.fromSrc).map { case (gf, src) =>
          gf -> erow.getOrElse(src, "")
        }.toMap
        val toProps = toKeyFields.zip(idx.toSrc).map { case (gf, src) =>
          gf -> erow.getOrElse(src, "")
        }.toMap
        val relVal = EntityVal.Rel(edgeType.label, relProps, fromProps, toProps)

        val otherKeySrc = if outgoing then idx.toSrc else idx.fromSrc
        val otherKeyVals = otherKeySrc.map(c => erow.getOrElse(c, ""))
        val otherIdFields = rightNodeType.id.map(_.name)
        if otherIdFields.size != otherKeyVals.size then
          fail(s"Key arity mismatch for ${edgeType.label}")

        val rightNode = resolveRightNode(
          rightNodeType,
          otherIdFields.zip(otherKeyVals).toMap,
          edgeType,
          erow,
          coLocated =
            rightNodeType.table.map(_.qualifiedTable) == edgeType.table.map(_.qualifiedTable)
        )
        if !propsMatch(rightNode.props, right.properties) then None
        else if prev.contains(rightAlias) then
          prev(rightAlias) match
            case existing: EntityVal.Node if existing.props == rightNode.props =>
              val row = prev + (relAlias -> relVal)
              if where.forall(eval(_, row)) then Some(row) else None
            case _ => None
        else
          val row = prev + (relAlias -> relVal) + (rightAlias -> rightNode)
          if where.forall(eval(_, row)) then Some(row) else None

  private def inferLeftAlias(
      rows: List[Row],
      edgeType: EdgeType,
      direction: Direction
  ): String =
    // Prefer a bound node whose label matches an endpoint of this edge.
    val candidates = rows.headOption.toList.flatMap(_.collect {
      case (alias, EntityVal.Node(label, _))
          if label == edgeType.fromNodeLabel || label == edgeType.toNodeLabel =>
        alias
    })
    candidates.lastOption.getOrElse(fail(s"No bound endpoint for :${edgeType.label}"))

  private def resolveOutgoing(
      direction: Direction,
      edge: EdgeType,
      leftAlias: String,
      rows: List[Row],
      rightLabel: Option[String]
  ): Boolean =
    val leftLabel = rows.headOption.flatMap(_.get(leftAlias)).collect {
      case EntityVal.Node(l, _) => l
    }.getOrElse(fail(s"Unbound $leftAlias"))
    direction match
      case Direction.Outgoing => true
      case Direction.Incoming => false
      case Direction.Both =>
        (leftLabel, rightLabel) match
          case (l, Some(r)) if l == edge.fromNodeLabel && r == edge.toNodeLabel => true
          case (l, Some(r)) if l == edge.toNodeLabel && r == edge.fromNodeLabel => false
          case (l, None) if l == edge.fromNodeLabel => true
          case (l, None) if l == edge.toNodeLabel   => false
          case _ =>
            fail(
              s"Cannot resolve undirected :${edge.label} between $leftLabel and ${rightLabel.getOrElse("?")}"
            )

  private def resolveRightNode(
      nodeType: NodeType,
      idMap: Map[String, String],
      edgeType: EdgeType,
      edgeRow: Map[String, String],
      coLocated: Boolean
  ): EntityVal.Node =
    if coLocated then
      val props = graph.nodeGraphFields(nodeType).map { gf =>
        graph.nodeSourceCol(nodeType, gf).toOption match
          case Some(src) => gf -> edgeRow.getOrElse(src, idMap.getOrElse(gf, ""))
          case None      => gf -> idMap.getOrElse(gf, "")
      }.toMap
      val withIds = props ++ idMap.collect {
        case (k, v) if graph.nodeGraphFields(nodeType).contains(k) => k -> v
      }
      EntityVal.Node(nodeType.label, withIds)
    else
      val idKey = nodeType.id.map(f => idMap.getOrElse(f.name, "")).mkString("\u0000")
      nodesFor(nodeType.label)._2.getOrElse(
        idKey,
        EntityVal.Node(nodeType.label, idMap)
      )

  private def snapshotTable(table: Table): List[Map[String, String]] =
    val cols = table.columnNames().asScala.toList
    table
      .stream()
      .iterator()
      .asScala
      .map(row => cols.map(c => c -> cellValue(row, c)).toMap)
      .toList

  private def scanNodes(nt: NodeType, table: Table): List[EntityVal.Node] =
    snapshotTable(table).map(row => rowToNodeFromMap(nt, row))

  private def rowToNodeFromMap(nt: NodeType, row: Map[String, String]): EntityVal.Node =
    val props = graph.nodeGraphFields(nt).flatMap { gf =>
      graph.nodeSourceCol(nt, gf).toOption.map(src => gf -> row.getOrElse(src, ""))
    }.toMap
    EntityVal.Node(nt.label, props)

  private def cellValue(row: tech.tablesaw.api.Row, col: String): String =
    if row.isMissing(col) then ""
    else
      try nullToEmpty(row.getString(col))
      catch
        case _: IllegalArgumentException =>
          Option(row.getObject(col)).map(_.toString).getOrElse("")

  private def propsMatch(props: Map[String, String], mapLit: Option[MapLiteral]): Boolean =
    mapLit match
      case None => true
      case Some(MapLiteral(entries)) =>
        entries.forall { case (k, v) =>
          props.get(k).contains(literalString(v))
        }

  private def literalString(expr: Expr): String =
    expr match
      case StringLit(v)  => v
      case IntegerLit(v) => v.toString
      case FloatLit(v)   => v.toString
      case BooleanLit(v) => v.toString
      case NullLit       => ""
      case _             => fail(s"Unsupported property-map value: $expr")

  private def eval(expr: Expr, row: Row): Boolean =
    expr match
      case And(l, r) => eval(l, row) && eval(r, row)
      case Or(l, r)  => eval(l, row) || eval(r, row)
      case Not(e)    => !eval(e, row)
      case Comparison(l, op, r) =>
        (l, r) match
          case (Variable(a), Variable(b)) =>
            cmpNodes(row, a, b, op)
          case _ =>
            cmpValues(evalValue(l, row), op, evalValue(r, row))
      case IsNull(e)    => evalValue(e, row) == null || evalValue(e, row) == ""
      case IsNotNull(e) =>
        val v = evalValue(e, row)
        v != null && v != ""
      case BooleanLit(v) => v
      case _             => fail(s"Unsupported WHERE expression: $expr")

  private def cmpNodes(row: Row, a: String, b: String, op: CmpOp): Boolean =
    (row.get(a), row.get(b)) match
      case (Some(EntityVal.Node(_, pa)), Some(EntityVal.Node(_, pb))) =>
        val ka = pa.toList.sortBy(_._1).mkString
        val kb = pb.toList.sortBy(_._1).mkString
        op match
          case CmpOp.Eq  => ka == kb
          case CmpOp.Neq => ka != kb
          case _         => fail("Only = / <> supported for node comparison")
      case _ => fail(s"Cannot compare $a and $b as nodes")

  private def cmpValues(l: String, op: CmpOp, r: String): Boolean =
    op match
      case CmpOp.Eq  => l == r
      case CmpOp.Neq => l != r
      case CmpOp.Lt  => l < r
      case CmpOp.Lte => l <= r
      case CmpOp.Gt  => l > r
      case CmpOp.Gte => l >= r

  private def evalValue(expr: Expr, row: Row): String =
    expr match
      case StringLit(v)  => v
      case IntegerLit(v) => v.toString
      case FloatLit(v)   => v.toString
      case BooleanLit(v) => v.toString
      case NullLit       => ""
      case Property(Variable(name), key) =>
        row.get(name) match
          case Some(EntityVal.Node(_, props)) => props.getOrElse(key, "")
          case Some(EntityVal.Rel(_, props, _, _)) => props.getOrElse(key, "")
          case None => fail(s"Unbound variable: $name")
      case Variable(name) =>
        row.get(name) match
          case Some(EntityVal.Node(_, props)) =>
            props.toList.sortBy(_._1).map(_._2).mkString("|")
          case Some(EntityVal.Rel(_, props, _, _)) =>
            props.toList.sortBy(_._1).map(_._2).mkString("|")
          case None => fail(s"Unbound variable: $name")
      case _ => fail(s"Unsupported value expression: $expr")

  private def materialize(rows: List[Row], ret: Return): Subgraph =
    val returnNames: Option[Set[String]] =
      if ret.items.size == 1 && ret.items.head.expr == Star then None
      else
        Some(ret.items.flatMap {
          case ReturnItem(Variable(n), _)          => Some(n)
          case ReturnItem(Property(Variable(n), _), _) => Some(n)
          case ReturnItem(Star, _)                 => None
          case _ => fail("Unsupported RETURN item in subgraph mode")
        }.toSet)

    val nodeMap = scala.collection.mutable.LinkedHashMap.empty[String, GraphNode]
    val relMap = scala.collection.mutable.LinkedHashMap.empty[String, GraphRelationship]

    rows.foreach: row =>
      row.foreach:
        case (alias, entity) =>
          if returnNames.forall(_.contains(alias)) then
            entity match
              case EntityVal.Node(label, props) =>
                val idKey = nodeIdKey(label, props)
                nodeMap.getOrElseUpdate(
                  idKey,
                  GraphNode(label, idKey, props)
                )
              case EntityVal.Rel(relType, props, fromKeys, toKeys) =>
                val idKey = relIdKey(relType, props, fromKeys, toKeys)
                relMap.getOrElseUpdate(
                  idKey,
                  GraphRelationship(relType, idKey, fromKeys, toKeys, props)
                )

    Subgraph(nodeMap.values.toList, relMap.values.toList)

  private def nodeIdKey(label: String, props: Map[String, String]): String =
    val nt = graph.schema.requireNode(label)
    val ids = nt.id.map(f => props.getOrElse(f.name, ""))
    s"$label|${ids.mkString("|")}"

  private def relIdKey(
      relType: String,
      props: Map[String, String],
      fromKeys: Map[String, String],
      toKeys: Map[String, String]
  ): String =
    val et = graph.schema.requireEdge(relType)
    val ids =
      if et.id.nonEmpty then et.id.map(f => props.getOrElse(f.name, ""))
      else fromKeys.values.toList ++ toKeys.values.toList
    s"$relType|${ids.mkString("|")}"

  private def fresh(prefix: String): String =
    anon += 1
    s"$prefix$anon"

  private def nullToEmpty(s: String): String =
    if s == null then "" else s
