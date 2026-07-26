package cypher2sql.sql

import cypher2sql.ast.*
import cypher2sql.parser.CypherParser
import cypher2sql.schema.*

object Cypher2Sql:
  def convert(cypher: String, schema: GraphSchema): Either[String, String] =
    convert(cypher, schema, TableSizes(Nil))

  def convert(
      cypher: String,
      schema: GraphSchema,
      tableSizes: TableSizes
  ): Either[String, String] =
    CypherParser.parse(cypher).flatMap(convert(_, schema, tableSizes))

  def convert(query: Query, schema: GraphSchema): Either[String, String] =
    convert(query, schema, TableSizes(Nil))

  def convert(
      query: Query,
      schema: GraphSchema,
      tableSizes: TableSizes
  ): Either[String, String] =
    SqlConverter(schema, tableSizes).convert(query)

private final class ConversionException(msg: String) extends RuntimeException(msg)

private enum Binding:
  case Node(alias: String, nodeType: NodeType, ds: ExternalDataSource)
  case Rel(alias: String, edgeType: EdgeType, ds: ExternalDataSource)

private final case class CteStep(name: String, body: String)

/**
 * ClickHouse-oriented SQL funnel:
 * each join CTE uses exactly two inputs —
 *   FROM <larger physical table>
 *   JOIN <smaller physical table | previous CTE> AS ...
 *
 * Intermediate CTEs are assumed smaller than any table in `tableSizes`.
 */
final class SqlConverter(schema: GraphSchema, tableSizes: TableSizes = TableSizes(Nil)):
  private var bindings = Map.empty[String, Binding]
  private var steps = Vector.empty[CteStep]
  private var cteBound = Set.empty[String]
  /** When translating WHERE for a join, qualify this alias against the base table. */
  private var exprBaseAlias: Option[String] = None
  /** Graph variable → SQL FROM-alias for the join currently being translated. */
  private var exprSqlAlias: Map[String, String] = Map.empty
  private var anonSeq = 0
  private var stepNum = 0

  def convert(query: Query): Either[String, String] =
    try Right(convertUnsafe(query))
    catch case e: ConversionException => Left(e.getMessage)

  private def fail(msg: String): Nothing = throw ConversionException(msg)

  private def convertUnsafe(query: Query): String =
    val matchClauses = query.clauses.collect { case m: Match => m }
    val withClauses = query.clauses.collect { case w: With => w }
    val returnClauses = query.clauses.collect { case r: Return => r }

    if withClauses.nonEmpty then fail("WITH clauses are not supported")
    if returnClauses.size != 1 then fail("Query must contain exactly one RETURN clause")
    if matchClauses.isEmpty then fail("Query must contain at least one MATCH clause")

    matchClauses.foreach(processMatch)
    renderFinal(returnClauses.head)

  // --- MATCH processing -------------------------------------------------------

  private def processMatch(m: Match): Unit =
    if m.optional then fail("OPTIONAL MATCH is not supported in binary-join CTE mode yet")

    val parts = m.pattern.parts.map(_.element)

    parts match
      case (n: NodePattern) :: Nil =>
        processNodeOnly(n, m.where)
      case other =>
        // Bind/seed path starts first so WHERE can resolve variables.
        other.foreach:
          case n: NodePattern =>
            processNodeOnly(n, None)
          case PathPattern(start, chain) =>
            processPath(start, chain, m.where)

  private def processNodeOnly(node: NodePattern, where: Option[Expr]): Unit =
    val alias =
      if node.variable.exists(bindings.contains) then
        validateExistingNode(node)
        node.variable.get
      else ensureFreshNode(node)
    if cteBound.contains(alias) then
      where.foreach(w => emitFilter(List(exprSql(w))))
    else
      val b = nodeBinding(alias)
      val preds =
        propertyMapPreds(alias, b.nodeType, node.properties) ++
          where.map(w => unqualifySeed(alias, b, exprSql(w))).toList
      emitSeed(alias, b.ds.qualifiedTable, preds)

  private def processPath(
      start: NodePattern,
      chain: List[(RelationshipPattern, NodePattern)],
      where: Option[Expr]
  ): Unit =
    val startAlreadyLive = start.variable.exists(cteBound.contains)

    val startAlias =
      if startAlreadyLive then
        validateExistingNode(start)
        start.variable.get
      else if start.variable.exists(bindings.contains) then
        validateExistingNode(start)
        start.variable.get
      else ensureFreshNode(start)

    // First path hop: fuse start-node scan into a physical/physical join (no seed CTE).
    val fuseFirstHop = !startAlreadyLive && steps.isEmpty && chain.nonEmpty
    val startOnlyWhere = where.filter(w => exprAliases(w).subsetOf(Set(startAlias)))
    val whereConsumedEarly = fuseFirstHop && startOnlyWhere.isDefined

    if !fuseFirstHop && !startAlreadyLive && steps.isEmpty then
      // Node-only paths are handled elsewhere; keep seed fallback for safety.
      val b = nodeBinding(startAlias)
      val seedWhere =
        propertyMapPreds(startAlias, b.nodeType, start.properties) ++
          startOnlyWhere.map(w => unqualifySeed(startAlias, b, exprSql(w))).toList
      emitSeed(startAlias, b.ds.qualifiedTable, seedWhere)

    var leftAlias = startAlias
    chain.zipWithIndex.foreach { case ((rel, right), idx) =>
      val isFirst = idx == 0
      val isLast = idx == chain.length - 1
      val whereForHop = if isLast && !whereConsumedEarly then where else None
      leftAlias = processHop(
        leftAlias,
        rel,
        right,
        whereForHop,
        deferredStartProps = if isFirst && fuseFirstHop then start.properties else None,
        deferredStartWhere = if isFirst && fuseFirstHop then startOnlyWhere else None
      )
    }

  private def processHop(
      leftAlias: String,
      rel: RelationshipPattern,
      right: NodePattern,
      where: Option[Expr],
      deferredStartProps: Option[MapLiteral] = None,
      deferredStartWhere: Option[Expr] = None
  ): String =
    if rel.length.nonEmpty then fail("Variable-length relationships are not supported")
    if rel.types.isEmpty then fail("Relationship type is required")
    if rel.types.size > 1 then fail(s"Multiple relationship types not supported: ${rel.types}")
    rel.properties.foreach(_ => fail("Relationship property maps are not supported yet"))

    val edgeType =
      schema.edge(rel.types.head).getOrElse(fail(s"Unknown relationship type: ${rel.types.head}"))
    val edgeDs =
      edgeType.table.getOrElse(fail(s"Relationship '${edgeType.label}' has no table mapping"))

    val leftNode = nodeBinding(leftAlias)
    val outgoing =
      resolveOutgoing(rel.direction, edgeType, leftNode.nodeType.label, right.labels.headOption)
    val expectedRight = if outgoing then edgeType.toNodeLabel else edgeType.fromNodeLabel

    val rightAlias = right.variable.getOrElse(freshAnon("n"))
    val rightIsNew = !cteBound.contains(rightAlias) && !bindings.contains(rightAlias)
    if rightIsNew then ensureNodeBound(rightAlias, right, expectedRight)
    else validateExistingNode(right.copy(variable = Some(rightAlias)))

    val relAlias = rel.variable.getOrElse(freshAnon("r"))
    if bindings.contains(relAlias) then fail(s"Variable '$relAlias' is already bound")
    bindings += relAlias -> Binding.Rel(relAlias, edgeType, edgeDs)

    val (fromAlias, toAlias) =
      if outgoing then (leftAlias, rightAlias) else (rightAlias, leftAlias)

    val leftLive = cteBound.contains(leftAlias)
    val rightLive = cteBound.contains(rightAlias)

    def translateWhere(baseAlias: String): List[String] =
      where match
        case None => Nil
        case Some(w) =>
          exprBaseAlias = Some(baseAlias)
          try List(exprSql(w))
          finally exprBaseAlias = None

    // First funnel hop: join start-node table to edge table (no prior CTE).
    if !leftLive && !rightLive then
      val edgeKeyFields = if outgoing then edgeType.fromKey else edgeType.toKey
      val edgeKeyCols = keySourceCols(edgeType, edgeKeyFields, edgeDs)
      val nodeIdCols = idSourceCols(leftNode)
      val otherAlias = rightAlias
      val otherIsNew = !cteBound.contains(otherAlias)
      val derivedCols: List[String] =
        if otherIsNew && nodeBinding(otherAlias).ds.qualifiedTable == edgeDs.qualifiedTable then
          deriveNodeColsFromEdge(
            otherAlias,
            relAlias,
            edgeType,
            edgeDs,
            nodeIsTo = otherAlias == toAlias
          )
        else Nil

      exprSqlAlias = Map(
        leftAlias -> leftAlias,
        relAlias -> relAlias
      ) ++ (if derivedCols.nonEmpty then Map(otherAlias -> relAlias) else Map.empty)
      val startPreds =
        propertyMapPredsOnBase(leftAlias, leftNode.nodeType, deferredStartProps) ++
          deferredStartWhere.map(exprSql).toList
      val rightPreds =
        if otherIsNew && derivedCols.nonEmpty then
          propertyMapPredsOnBase(relAlias, nodeBinding(otherAlias).nodeType, right.properties)
        else Nil
      val whereSql = startPreds ++ translateWhere(relAlias) ++ rightPreds
      exprSqlAlias = Map.empty
      exprBaseAlias = None

      emitPhysicalJoin(
        nodeDs = leftNode.ds,
        nodeAlias = leftAlias,
        edgeDs = edgeDs,
        edgeAlias = relAlias,
        nodeIdCols = nodeIdCols,
        edgeKeyCols = edgeKeyCols,
        whereSql = whereSql,
        introduce = List(leftAlias, relAlias),
        extraCols = derivedCols
      )
      if derivedCols.nonEmpty then cteBound += otherAlias

      if otherIsNew && derivedCols.isEmpty then
        val other = nodeBinding(otherAlias)
        val (baseCols, prevFields) =
          if otherAlias == toAlias then
            (idSourceCols(other), keyGraphFields(edgeType, edgeType.toKey))
          else
            (idSourceCols(other), keyGraphFields(edgeType, edgeType.fromKey))
        val propPreds = propertyMapPredsOnBase(otherAlias, other.nodeType, right.properties)
        emitJoin(
          baseTable = other.ds.qualifiedTable,
          baseAlias = otherAlias,
          onBaseCols = baseCols,
          onPrevVar = relAlias,
          onPrevFields = prevFields,
          whereSql = translateWhere(otherAlias) ++ propPreds,
          introduce = List(otherAlias),
          extraCols = Nil
        )
      rightAlias
    else
      // Join edge table to previous CTE. Anchor on whichever endpoint is already filtered.
      val (onBaseCols, onPrevVar, onPrevFields) =
        if leftLive && outgoing then
          (keySourceCols(edgeType, edgeType.fromKey, edgeDs), leftAlias, idGraphFields(leftNode))
        else if leftLive && !outgoing then
          (keySourceCols(edgeType, edgeType.toKey, edgeDs), leftAlias, idGraphFields(leftNode))
        else if rightLive && outgoing then
          (keySourceCols(edgeType, edgeType.toKey, edgeDs), rightAlias, idGraphFields(nodeBinding(rightAlias)))
        else if rightLive && !outgoing then
          (keySourceCols(edgeType, edgeType.fromKey, edgeDs), rightAlias, idGraphFields(nodeBinding(rightAlias)))
        else
          fail(s"Hop :${edgeType.label} has no filtered endpoint to join against")

      val otherAlias = if leftLive then rightAlias else leftAlias
      val otherIsNew = !cteBound.contains(otherAlias)
      val derivedCols: List[String] =
        if otherIsNew && nodeBinding(otherAlias).ds.qualifiedTable == edgeDs.qualifiedTable then
          deriveNodeColsFromEdge(
            otherAlias,
            relAlias,
            edgeType,
            edgeDs,
            nodeIsTo = otherAlias == toAlias
          )
        else Nil

      // Push filters into the join that first exposes the referenced columns.
      if otherIsNew && derivedCols.isEmpty then
        emitJoin(
          baseTable = edgeDs.qualifiedTable,
          baseAlias = relAlias,
          onBaseCols = onBaseCols,
          onPrevVar = onPrevVar,
          onPrevFields = onPrevFields,
          whereSql = Nil,
          introduce = List(relAlias),
          extraCols = Nil
        )
        val other = nodeBinding(otherAlias)
        val (baseCols, prevFields) =
          if otherAlias == toAlias then
            (idSourceCols(other), keyGraphFields(edgeType, edgeType.toKey))
          else
            (idSourceCols(other), keyGraphFields(edgeType, edgeType.fromKey))
        val propPreds = propertyMapPredsOnBase(otherAlias, other.nodeType, right.properties)
        emitJoin(
          baseTable = other.ds.qualifiedTable,
          baseAlias = otherAlias,
          onBaseCols = baseCols,
          onPrevVar = relAlias,
          onPrevFields = prevFields,
          whereSql = translateWhere(otherAlias) ++ propPreds,
          introduce = List(otherAlias),
          extraCols = Nil
        )
      else
        val propPreds =
          if otherIsNew then
            propertyMapPredsOnBase(relAlias, nodeBinding(otherAlias).nodeType, right.properties)
          else Nil
        emitJoin(
          baseTable = edgeDs.qualifiedTable,
          baseAlias = relAlias,
          onBaseCols = onBaseCols,
          onPrevVar = onPrevVar,
          onPrevFields = onPrevFields,
          whereSql = translateWhere(relAlias) ++ propPreds,
          introduce = List(relAlias),
          extraCols = derivedCols
        )
        if derivedCols.nonEmpty then cteBound += otherAlias

      rightAlias

  /** Columns that alias edge base-table fields onto a co-located node variable. */
  private def deriveNodeColsFromEdge(
      nodeAlias: String,
      relAlias: String,
      edgeType: EdgeType,
      edgeDs: ExternalDataSource,
      nodeIsTo: Boolean
  ): List[String] =
    val node = nodeBinding(nodeAlias)
    val keyFields = if nodeIsTo then edgeType.toKey else edgeType.fromKey
    val keySources = keySourceCols(edgeType, keyFields, edgeDs)
    val idFields = idGraphFields(node)
    if keySources.size != idFields.size then
      fail(s"Cannot derive node '$nodeAlias' ids from edge '${edgeType.label}' keys")

    nodeGraphFields(node).map: field =>
      val src =
        idFields.indexOf(field) match
          case i if i >= 0 =>
            qual(relAlias, keySources(i))
          case _ =>
            nodeColumn(node.nodeType, field) match
              case Some(ncol) if keySources.contains(ncol) =>
                qual(relAlias, ncol)
              case Some(ncol) =>
                // Mapped attribute sharing a physical column with an edge key/id.
                relGraphFields(Binding.Rel(relAlias, edgeType, edgeDs))
                  .flatMap(rf => edgeColumn(edgeType, edgeDs, rf).map(rf -> _))
                  .find((_, col) => col == ncol)
                  .map((rf, _) => qual(relAlias, edgeColumn(edgeType, edgeDs, rf).get))
                  .getOrElse(qual(relAlias, ncol))
              case None =>
                fail(s"Cannot derive ${nodeAlias}.$field from edge $relAlias")
      s"$src AS ${cteCol(nodeAlias, field)}"
  // --- Emit primitives --------------------------------------------------------

  private def emitSeed(alias: String, table: String, whereSql: List[String]): Unit =
    val b = nodeBinding(alias)
    val cols = nodeGraphFields(b).map: field =>
      val col = nodeColumn(b.nodeType, field).getOrElse(fail(s"No column for $alias.$field"))
      s"${qid(col)} AS ${cteCol(alias, field)}"
    val sb = new StringBuilder
    sb.append("SELECT\n  ")
    sb.append(cols.mkString(",\n  "))
    sb.append(s"\nFROM $table")
    if whereSql.nonEmpty then
      sb.append("\nWHERE ")
      // seed WHERE uses unqualified / table columns; rewrite `alias.field` refs to source cols
      sb.append(whereSql.map(unqualifySeed(alias, b, _)).mkString(" AND "))
    flushStep(sb.toString)
    cteBound += alias

  /** Physical table row count; unknown tables count as 0 (smaller than known ones). */
  private def tableRows(ds: ExternalDataSource): Long =
    tableSizes.rows(ds.schema, ds.table).orElse(tableSizes.rows(ds.table)).getOrElse(0L)

  /**
   * First-funnel join between two physical tables.
   * Larger table is placed first in FROM (CTE/intermediates are always smaller).
   */
  private def emitPhysicalJoin(
      nodeDs: ExternalDataSource,
      nodeAlias: String,
      edgeDs: ExternalDataSource,
      edgeAlias: String,
      nodeIdCols: List[String],
      edgeKeyCols: List[String],
      whereSql: List[String],
      introduce: List[String],
      extraCols: List[String]
  ): Unit =
    if nodeIdCols.size != edgeKeyCols.size then
      fail(s"Join key arity mismatch: $nodeIdCols vs $edgeKeyCols")

    val nodeFirst = tableRows(nodeDs) >= tableRows(edgeDs)
    val (leftTable, leftAlias, rightTable, rightAlias, on) =
      if nodeFirst then
        (
          nodeDs.qualifiedTable,
          nodeAlias,
          edgeDs.qualifiedTable,
          edgeAlias,
          edgeKeyCols.zip(nodeIdCols).map { case (ec, nc) =>
            s"${qual(edgeAlias, ec)} = ${qual(nodeAlias, nc)}"
          }
        )
      else
        (
          edgeDs.qualifiedTable,
          edgeAlias,
          nodeDs.qualifiedTable,
          nodeAlias,
          nodeIdCols.zip(edgeKeyCols).map { case (nc, ec) =>
            s"${qual(nodeAlias, nc)} = ${qual(edgeAlias, ec)}"
          }
        )

    val cols =
      introduce.flatMap: name =>
        val sqlAlias = if name == edgeAlias then edgeAlias else nodeAlias
        projectFromAlias(sqlAlias, name)
      ++ extraCols
    if cols.isEmpty then fail("Physical join projected no columns")

    val sb = new StringBuilder
    sb.append("SELECT\n  ")
    sb.append(cols.mkString(",\n  "))
    sb.append(s"\nFROM $leftTable AS ${qid(leftAlias)}")
    sb.append(s"\nINNER JOIN $rightTable AS ${qid(rightAlias)}")
    sb.append(s" ON ${on.mkString(" AND ")}")
    if whereSql.nonEmpty then
      sb.append("\nWHERE ")
      sb.append(whereSql.mkString(" AND "))
    flushStep(sb.toString)
    cteBound ++= introduce

  /**
   * Binary join step:
   *   FROM <baseTable> AS <baseAlias>
   *   INNER JOIN <prev> AS prev ON base.cols = prev.`var.field`
   */
  private def emitJoin(
      baseTable: String,
      baseAlias: String,
      onBaseCols: List[String],
      onPrevVar: String,
      onPrevFields: List[String],
      whereSql: List[String],
      introduce: List[String],
      extraCols: List[String]
  ): Unit =
    if steps.isEmpty then fail("Join step requires a previous CTE")
    if onBaseCols.size != onPrevFields.size then
      fail(s"Join key arity mismatch: $onBaseCols vs $onPrevFields")

    val on = onBaseCols.zip(onPrevFields).map { case (bc, pf) =>
      s"${qual(baseAlias, bc)} = prev.${cteCol(onPrevVar, pf)}"
    }

    val carried = cteBound.toList.sorted.flatMap(projectFromPrev)
    val introduced = introduce.flatMap(projectFromAlias(baseAlias, _))
    val cols = carried ++ introduced ++ extraCols
    if cols.isEmpty then fail("Join step projected no columns")

    val sb = new StringBuilder
    sb.append("SELECT\n  ")
    sb.append(cols.mkString(",\n  "))
    sb.append(s"\nFROM $baseTable AS ${qid(baseAlias)}")
    sb.append(s"\nINNER JOIN ${steps.last.name} AS prev")
    sb.append(s" ON ${on.mkString(" AND ")}")
    if whereSql.nonEmpty then
      sb.append("\nWHERE ")
      sb.append(whereSql.mkString(" AND "))
    flushStep(sb.toString)
    cteBound ++= introduce

  private def emitFilter(whereSql: List[String]): Unit =
    if whereSql.isEmpty then return
    if steps.isEmpty then fail("Filter step requires a previous CTE")
    val cols = cteBound.toList.sorted.flatMap(projectFromPrev)
    val sb = new StringBuilder
    sb.append("SELECT\n  ")
    sb.append(cols.mkString(",\n  "))
    sb.append(s"\nFROM ${steps.last.name} AS prev")
    sb.append("\nWHERE ")
    sb.append(whereSql.mkString(" AND "))
    flushStep(sb.toString)

  private def flushStep(body: String): Unit =
    stepNum += 1
    steps :+= CteStep(s"step$stepNum", body)

  private def projectFromPrev(name: String): List[String] =
    graphFields(name).map: field =>
      val c = cteCol(name, field)
      s"prev.$c AS $c"

  private def projectFromAlias(sqlAlias: String, name: String): List[String] =
    bindings.get(name) match
      case Some(n: Binding.Node) =>
        nodeGraphFields(n).map: field =>
          val col = nodeColumn(n.nodeType, field).getOrElse(fail(s"No column for $name.$field"))
          s"${qual(sqlAlias, col)} AS ${cteCol(name, field)}"
      case Some(r: Binding.Rel) =>
        relGraphFields(r).map: field =>
          val col = edgeColumn(r.edgeType, r.ds, field).getOrElse(fail(s"No column for $name.$field"))
          s"${qual(sqlAlias, col)} AS ${cteCol(name, field)}"
      case None =>
        fail(s"Cannot project unbound '$name' from alias '$sqlAlias'")

  private def graphFields(name: String): List[String] =
    bindings.get(name) match
      case Some(n: Binding.Node) => nodeGraphFields(n)
      case Some(r: Binding.Rel)  => relGraphFields(r)
      case None                  => fail(s"Unbound variable: $name")

  // --- Binding helpers --------------------------------------------------------

  private def ensureFreshNode(node: NodePattern): String =
    val alias = node.variable.getOrElse(freshAnon("n"))
    bindings.get(alias) match
      case Some(_) =>
        fail(s"Variable '$alias' is already bound")
      case None =>
        val label = node.labels.headOption.getOrElse(
          fail(s"Node variable '$alias' requires a label on first mention")
        )
        if node.labels.size > 1 then fail(s"Multiple labels not supported: ${node.labels}")
        val nodeType = schema.node(label).getOrElse(fail(s"Unknown node label: $label"))
        val ds = nodeType.table.getOrElse(fail(s"Node label '$label' has no table mapping"))
        bindings += alias -> Binding.Node(alias, nodeType, ds)
        alias

  private def ensureNodeBound(alias: String, pattern: NodePattern, expectedLabel: String): Unit =
    bindings.get(alias) match
      case Some(Binding.Node(_, nt, _)) =>
        if pattern.labels.nonEmpty && !pattern.labels.contains(nt.label) then
          fail(s"Variable '$alias' label mismatch")
      case Some(_: Binding.Rel) =>
        fail(s"Variable '$alias' is already bound as a relationship")
      case None =>
        if pattern.labels.size > 1 then fail(s"Multiple labels not supported: ${pattern.labels}")
        val resolved = pattern.labels.headOption.getOrElse(expectedLabel)
        if resolved != expectedLabel then
          fail(s"Expected label $expectedLabel for '$alias', got $resolved")
        val nodeType =
          schema.node(resolved).getOrElse(fail(s"Unknown node label: $resolved"))
        val ds = nodeType.table.getOrElse(fail(s"Node label '$resolved' has no table mapping"))
        bindings += alias -> Binding.Node(alias, nodeType, ds)

  private def validateExistingNode(node: NodePattern): Unit =
    val alias = node.variable.getOrElse(fail("Expected bound node variable"))
    bindings.get(alias) match
      case Some(Binding.Node(_, nt, _)) =>
        if node.labels.nonEmpty && !node.labels.contains(nt.label) then
          fail(s"Variable '$alias' label mismatch")
      case Some(_) => fail(s"Variable '$alias' is not a node")
      case None    => fail(s"Unbound variable: $alias")

  private def resolveOutgoing(
      direction: Direction,
      edge: EdgeType,
      leftLabel: String,
      rightLabel: Option[String]
  ): Boolean =
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

  private def nodeBinding(alias: String): Binding.Node =
    bindings.get(alias) match
      case Some(n: Binding.Node) => n
      case Some(_) => fail(s"Variable '$alias' is not a node")
      case None    => fail(s"Unbound variable: $alias")

  private def freshAnon(prefix: String): String =
    anonSeq += 1
    s"_$prefix$anonSeq"

  private def idGraphFields(node: Binding.Node): List[String] =
    node.nodeType.id.map(_.name)

  private def keyGraphFields(edge: EdgeType, keys: List[GraphField]): List[String] =
    keys.map(_.name)

  private def idSourceCols(node: Binding.Node): List[String] =
    node.nodeType.id.map: field =>
      nodeColumn(node.nodeType, field.name).getOrElse(
        fail(s"No SQL column for id field '${field.name}' on ${node.nodeType.label}")
      )

  private def keySourceCols(
      edge: EdgeType,
      keys: List[GraphField],
      ds: ExternalDataSource
  ): List[String] =
    keys.map: field =>
      ds.sourceFor(field.name).getOrElse(
        fail(s"No SQL column mapping for edge key '${field.name}' on ${edge.label}")
      )

  private def propertyMapPreds(
      alias: String,
      nodeType: NodeType,
      props: Option[MapLiteral]
  ): List[String] =
    props.toList.flatMap(_.entries.map { case (key, value) =>
      s"${refVar(alias, key)} = ${exprSql(value)}"
    })

  /** Property-map predicates qualified against the current join base table. */
  private def propertyMapPredsOnBase(
      baseAlias: String,
      nodeType: NodeType,
      props: Option[MapLiteral]
  ): List[String] =
    props.toList.flatMap(_.entries.map { case (key, value) =>
      val col = nodeColumn(nodeType, key).getOrElse(
        fail(s"Unknown property '$key' on node '${nodeType.label}'")
      )
      s"${qual(baseAlias, col)} = ${exprSql(value)}"
    })

  private def nodeColumn(nodeType: NodeType, graphField: String): Option[String] =
    nodeType.table.flatMap(_.sourceFor(graphField))
      .orElse(Option.when(nodeType.attributes.exists(_.name == graphField))(graphField))
      .orElse(Option.when(nodeType.id.exists(_.name == graphField))(graphField))

  private def edgeColumn(edge: EdgeType, ds: ExternalDataSource, graphField: String): Option[String] =
    ds.sourceFor(graphField)
      .orElse(Option.when(edge.attributes.exists(_.name == graphField))(graphField))
      .orElse(
        Option.when(
          edge.id.exists(_.name == graphField) ||
            edge.fromKey.exists(_.name == graphField) ||
            edge.toKey.exists(_.name == graphField)
        )(graphField)
      )

  private def nodeGraphFields(n: Binding.Node): List[String] =
    if n.nodeType.attributes.nonEmpty then n.nodeType.attributes.map(_.name)
    else n.nodeType.id.map(_.name)

  private def relGraphFields(r: Binding.Rel): List[String] =
    (r.edgeType.id ++ r.edgeType.fromKey ++ r.edgeType.toKey ++ r.edgeType.attributes)
      .map(_.name)
      .distinct

  /** Reference a graph field — prev CTE, current join base table, or seed column. */
  private def refVar(alias: String, graphField: String): String =
    if cteBound.contains(alias) then s"prev.${cteCol(alias, graphField)}"
    else
      bindings.get(alias) match
        case Some(n: Binding.Node) =>
          val col = nodeColumn(n.nodeType, graphField).getOrElse(
            fail(s"Unknown property '$graphField' on node '${n.nodeType.label}'")
          )
          // Physical join: graph var → its SQL FROM alias.
          // Single-base join: qualify against exprBaseAlias.
          // Seed WHERE: unqualified source column.
          exprSqlAlias
            .get(alias)
            .orElse(exprBaseAlias)
            .map(sqlAlias => qual(sqlAlias, col))
            .getOrElse(qid(col))
        case Some(r: Binding.Rel) =>
          val col = edgeColumn(r.edgeType, r.ds, graphField).getOrElse(
            fail(s"Unknown property '$graphField' on relationship '${r.edgeType.label}'")
          )
          qual(exprSqlAlias.getOrElse(alias, alias), col)
        case None =>
          fail(s"Unbound variable: $alias")

  private def cteCol(alias: String, graphField: String): String =
    s"`$alias.$graphField`"

  private def exprAliases(expr: Expr): Set[String] =
    expr match
      case Variable(name)                   => Set(name)
      case Property(Variable(name), _)      => Set(name)
      case Property(inner, _)               => exprAliases(inner)
      case Not(e)                           => exprAliases(e)
      case And(l, r)                        => exprAliases(l) ++ exprAliases(r)
      case Or(l, r)                         => exprAliases(l) ++ exprAliases(r)
      case Xor(l, r)                        => exprAliases(l) ++ exprAliases(r)
      case Comparison(l, _, r)              => exprAliases(l) ++ exprAliases(r)
      case Add(l, r)                        => exprAliases(l) ++ exprAliases(r)
      case Subtract(l, r)                   => exprAliases(l) ++ exprAliases(r)
      case Multiply(l, r)                   => exprAliases(l) ++ exprAliases(r)
      case Divide(l, r)                     => exprAliases(l) ++ exprAliases(r)
      case Modulo(l, r)                     => exprAliases(l) ++ exprAliases(r)
      case Pow(l, r)                        => exprAliases(l) ++ exprAliases(r)
      case UnaryPlus(e)                     => exprAliases(e)
      case UnaryMinus(e)                    => exprAliases(e)
      case IsNull(e)                        => exprAliases(e)
      case IsNotNull(e)                     => exprAliases(e)
      case In(l, r)                         => exprAliases(l) ++ exprAliases(r)
      case StartsWith(l, r)                 => exprAliases(l) ++ exprAliases(r)
      case EndsWith(l, r)                   => exprAliases(l) ++ exprAliases(r)
      case Contains(l, r)                   => exprAliases(l) ++ exprAliases(r)
      case RegexMatch(l, r)                 => exprAliases(l) ++ exprAliases(r)
      case ListLiteral(els)                 => els.flatMap(exprAliases).toSet
      case FunctionInvocation(_, _, args)   => args.flatMap(exprAliases).toSet
      case HasLabels(Variable(name), _)     => Set(name)
      case HasLabels(inner, _)              => exprAliases(inner)
      case ContainerIndex(e, i)             => exprAliases(e) ++ exprAliases(i)
      case MapLiteral(entries)              => entries.flatMap((_, v) => exprAliases(v)).toSet
      case _: Literal | Parameter(_) | Star => Set.empty

  private def unqualifySeed(alias: String, b: Binding.Node, whereSql: String): String =
    // Replace prev.`alias.field` or `alias.field` with source column for seed FROM table.
    var s = whereSql
    nodeGraphFields(b).foreach: field =>
      val col = nodeColumn(b.nodeType, field).getOrElse(field)
      s = s.replace(s"prev.${cteCol(alias, field)}", qid(col))
      s = s.replace(cteCol(alias, field), qid(col))
      s = s.replace(s"$alias.$field", qid(col))
    s

  // --- Expressions ------------------------------------------------------------

  private def exprSql(expr: Expr): String =
    expr match
      case Variable(name) =>
        bindings.get(name) match
          case Some(n: Binding.Node) => refVar(n.alias, idGraphFields(n).head)
          case Some(r: Binding.Rel)  => refVar(r.alias, relGraphFields(r).head)
          case None                  => fail(s"Unbound variable: $name")

      case Property(Variable(name), key) => refVar(name, key)
      case Property(_, _) => fail("Nested property access is not supported")

      case StringLit(v)  => literalString(v)
      case IntegerLit(v) => v.toString
      case FloatLit(v)   => v.toString
      case BooleanLit(v) => if v then "TRUE" else "FALSE"
      case NullLit       => "NULL"
      case Parameter(n)  => s"{$n}"

      case Not(e)    => s"(NOT ${exprSql(e)})"
      case And(l, r) => s"(${exprSql(l)} AND ${exprSql(r)})"
      case Or(l, r)  => s"(${exprSql(l)} OR ${exprSql(r)})"
      case Xor(l, r) => s"(${exprSql(l)} XOR ${exprSql(r)})"

      case Comparison(l, op, r) =>
        (l, r) match
          case (vl: Variable, vr: Variable) => nodeIdComparison(vl.name, vr.name, op)
          case _ => s"${exprSql(l)} ${cmpOp(op)} ${exprSql(r)}"

      case Add(l, r)      => s"(${exprSql(l)} + ${exprSql(r)})"
      case Subtract(l, r) => s"(${exprSql(l)} - ${exprSql(r)})"
      case Multiply(l, r) => s"(${exprSql(l)} * ${exprSql(r)})"
      case Divide(l, r)   => s"(${exprSql(l)} / ${exprSql(r)})"
      case Modulo(l, r)   => s"(${exprSql(l)} % ${exprSql(r)})"
      case Pow(l, r)      => s"pow(${exprSql(l)}, ${exprSql(r)})"
      case UnaryPlus(e)   => s"(+${exprSql(e)})"
      case UnaryMinus(e)  => s"(-${exprSql(e)})"

      case IsNull(e)       => s"(${exprSql(e)} IS NULL)"
      case IsNotNull(e)    => s"(${exprSql(e)} IS NOT NULL)"
      case In(l, r)         => s"(${exprSql(l)} IN ${exprSql(r)})"
      case StartsWith(l, r) => s"(startsWith(${exprSql(l)}, ${exprSql(r)}))"
      case EndsWith(l, r)   => s"(endsWith(${exprSql(l)}, ${exprSql(r)}))"
      case Contains(l, r)   => s"(positionCaseInsensitive(${exprSql(l)}, ${exprSql(r)}) > 0)"
      case RegexMatch(l, r) => s"match(${exprSql(l)}, ${exprSql(r)})"

      case ListLiteral(els) => els.map(exprSql).mkString("(", ", ", ")")
      case FunctionInvocation(name, distinct, args) =>
        val d = if distinct then "DISTINCT " else ""
        s"$name($d${args.map(exprSql).mkString(", ")})"

      case HasLabels(Variable(name), labels) =>
        val n = nodeBinding(name)
        if labels.forall(_ == n.nodeType.label) then "TRUE" else "FALSE"
      case HasLabels(_, _) => fail("Unsupported label predicate")

      case MapLiteral(_) | ContainerIndex(_, _) | Star =>
        fail(s"Unsupported expression in SQL conversion: $expr")

  private def nodeIdComparison(left: String, right: String, op: CmpOp): String =
    val l = nodeBinding(left)
    val r = nodeBinding(right)
    val lcols = idGraphFields(l)
    val rcols = idGraphFields(r)
    if lcols.size != rcols.size then fail(s"Cannot compare nodes $left and $right")
    val parts = lcols.zip(rcols).map { case (lc, rc) =>
      s"${refVar(l.alias, lc)} ${cmpOp(op)} ${refVar(r.alias, rc)}"
    }
    if parts.size == 1 then parts.head else parts.mkString("(", " AND ", ")")

  private def cmpOp(op: CmpOp): String =
    op match
      case CmpOp.Eq  => "="
      case CmpOp.Neq => "<>"
      case CmpOp.Lt  => "<"
      case CmpOp.Lte => "<="
      case CmpOp.Gt  => ">"
      case CmpOp.Gte => ">="

  private def literalString(v: String): String =
    "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"

  private def qual(alias: String, column: String): String =
    s"${qid(alias)}.${qid(column)}"

  private def qid(name: String): String =
    if name.matches("[A-Za-z_][A-Za-z0-9_]*") then name
    else s"`${name.replace("`", "``")}`"

  // --- Final render -----------------------------------------------------------

  private def renderFinal(ret: Return): String =
    val selectItems =
      if ret.items.size == 1 && ret.items.head.expr == Star then
        bindings.keys.toList.sorted.flatMap(projectFinalVar)
      else ret.items.flatMap(projectReturnItem)

    if selectItems.isEmpty then fail("RETURN produced no columns")
    if steps.isEmpty then fail("No CTE steps generated")

    val sb = new StringBuilder
    sb.append("WITH\n")
    steps.zipWithIndex.foreach { case (step, idx) =>
      if idx > 0 then sb.append(",\n")
      sb.append(s"${step.name} AS (\n")
      sb.append(indent(step.body, 2))
      sb.append("\n)")
    }
    sb.append("\nSELECT\n  ")
    sb.append(selectItems.mkString(",\n  "))
    sb.append(s"\nFROM ${steps.last.name} AS prev")
    if ret.orderBy.nonEmpty then
      // ORDER BY refs use prev.`var.field`
      val saved = cteBound
      cteBound = bindings.keySet
      sb.append("\nORDER BY ")
      sb.append(
        ret.orderBy
          .map(si => s"${exprSql(si.expr)}${if si.ascending then "" else " DESC"}")
          .mkString(", ")
      )
      cteBound = saved
    ret.skip.foreach: s =>
      cteBound = bindings.keySet
      sb.append(s"\nOFFSET ${exprSql(s)}")
    ret.limit.foreach: l =>
      cteBound = bindings.keySet
      sb.append(s"\nLIMIT ${exprSql(l)}")
    sb.toString

  private def indent(text: String, spaces: Int): String =
    val pad = " " * spaces
    text.split("\n", -1).map(pad + _).mkString("\n")

  private def projectReturnItem(item: ReturnItem): List[String] =
    item.expr match
      case Variable(name) =>
        val cols = projectFinalVar(name)
        item.alias match
          case None => cols
          case Some(a) if cols.size == 1 =>
            List(cols.head.replaceAll(" AS `[^`]+`$", s" AS ${qid(a)}"))
          case Some(_) => fail(s"Cannot alias expanded variable '$name'")
      case Star => fail("RETURN * mixed with other items is not supported")
      case other =>
        cteBound = bindings.keySet
        val sql = exprSql(other)
        val alias = item.alias.getOrElse(defaultAlias(other))
        List(s"$sql AS ${qid(alias)}")

  private def projectFinalVar(name: String): List[String] =
    graphFields(name).map: field =>
      val col = cteCol(name, field)
      s"prev.$col AS $col"

  private def defaultAlias(expr: Expr): String =
    expr match
      case Property(Variable(n), key) => s"$n.$key"
      case Variable(n)                => n
      case _                          => "expr"
