package cypher2sql.sql

import cypher2sql.ast.*
import cypher2sql.parser.CypherParser
import cypher2sql.schema.*

object Cypher2Sql:
  def convert(cypher: String, schema: GraphSchema): Either[String, String] =
    CypherParser.parse(cypher).flatMap(convert(_, schema))

  def convert(query: Query, schema: GraphSchema): Either[String, String] =
    SqlConverter(schema).convert(query)

private final class ConversionException(msg: String) extends RuntimeException(msg)

private enum Binding:
  case Node(alias: String, nodeType: NodeType, ds: ExternalDataSource)
  case Rel(alias: String, edgeType: EdgeType, ds: ExternalDataSource)

private final case class FromItem(table: String, alias: String)
private final case class JoinItem(
    joinType: String,
    table: String,
    alias: String,
    on: List[String]
)

final class SqlConverter(schema: GraphSchema):
  private var bindings = Map.empty[String, Binding]
  private var fromItem: Option[FromItem] = None
  private var joins = Vector.empty[JoinItem]
  private var wherePreds = Vector.empty[String]
  private var anonSeq = 0

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
    render(returnClauses.head)

  private def processMatch(m: Match): Unit =
    val joinType = if m.optional then "LEFT JOIN" else "INNER JOIN"
    val pendingOn = scala.collection.mutable.ArrayBuffer.empty[String]

    m.pattern.parts.foreach: part =>
      processElement(part.element, joinType, pendingOn)

    m.where.foreach: w =>
      val sql = exprSql(w)
      if m.optional then pendingOn += sql
      else wherePreds :+= sql

    if pendingOn.nonEmpty then
      if joins.nonEmpty then
        val last = joins.last
        joins = joins.init :+ last.copy(on = last.on ++ pendingOn.toVector)
      else wherePreds ++= pendingOn.toVector

  private def processElement(
      element: PatternElement,
      joinType: String,
      pendingOn: scala.collection.mutable.ArrayBuffer[String]
  ): Unit =
    element match
      case n: NodePattern =>
        bindNode(n, joinType, pendingOn, deferTable = false)
        ()
      case PathPattern(start, chain) =>
        // If FROM already exists, defer joining a new path-start node until the hop
        // can attach it via relationship keys (avoids JOIN without ON).
        val defer = fromItem.nonEmpty
        var left = bindNode(start, joinType, pendingOn, deferTable = defer)
        chain.foreach { case (rel, right) =>
          left = bindHop(left, rel, right, joinType, pendingOn)
        }

  private def bindNode(
      node: NodePattern,
      joinType: String,
      pendingOn: scala.collection.mutable.ArrayBuffer[String],
      deferTable: Boolean
  ): String =
    val alias = node.variable.getOrElse(freshAnon("n"))
    bindings.get(alias) match
      case Some(Binding.Node(_, existing, _)) =>
        if node.labels.nonEmpty && !node.labels.contains(existing.label) then
          fail(
            s"Variable '$alias' already bound to label ${existing.label}, got ${node.labels}"
          )
        node.properties.foreach(props => pendingOn ++= propertyMapPreds(alias, existing, props))
        alias
      case Some(_: Binding.Rel) =>
        fail(s"Variable '$alias' is already bound as a relationship")
      case None =>
        val label = node.labels.headOption.getOrElse(
          fail(s"Node variable '$alias' requires a label on first mention")
        )
        if node.labels.size > 1 then fail(s"Multiple labels not supported: ${node.labels}")
        val nodeType = schema.node(label).getOrElse(fail(s"Unknown node label: $label"))
        val ds = nodeType.table.getOrElse(fail(s"Node label '$label' has no table mapping"))
        bindings += alias -> Binding.Node(alias, nodeType, ds)
        if !deferTable then addTable(ds.qualifiedTable, alias, joinType, Nil)
        node.properties.foreach: props =>
          val preds = propertyMapPreds(alias, nodeType, props)
          if fromItem.exists(_.alias == alias) && joins.isEmpty then wherePreds ++= preds
          else pendingOn ++= preds
        alias

  private def bindHop(
      leftAlias: String,
      rel: RelationshipPattern,
      right: NodePattern,
      joinType: String,
      pendingOn: scala.collection.mutable.ArrayBuffer[String]
  ): String =
    if rel.length.nonEmpty then fail("Variable-length relationships are not supported")
    if rel.types.isEmpty then fail("Relationship type is required")
    if rel.types.size > 1 then fail(s"Multiple relationship types not supported: ${rel.types}")

    val edgeType =
      schema.edge(rel.types.head).getOrElse(fail(s"Unknown relationship type: ${rel.types.head}"))
    val edgeDs =
      edgeType.table.getOrElse(fail(s"Relationship '${edgeType.label}' has no table mapping"))

    val leftNode = nodeBinding(leftAlias)
    val outgoing =
      resolveOutgoing(rel.direction, edgeType, leftNode.nodeType.label, right.labels.headOption)

    val expectedRightLabel =
      if outgoing then edgeType.toNodeLabel else edgeType.fromNodeLabel
    val rightAlias = right.variable.getOrElse(freshAnon("n"))
    ensureNodeBound(rightAlias, right, expectedRightLabel)

    val relAlias = rel.variable.getOrElse(freshAnon("r"))
    if bindings.contains(relAlias) then fail(s"Variable '$relAlias' is already bound")
    bindings += relAlias -> Binding.Rel(relAlias, edgeType, edgeDs)

    val (fromAlias, toAlias) =
      if outgoing then (leftAlias, rightAlias) else (rightAlias, leftAlias)
    val fromB = nodeBinding(fromAlias)
    val toB = nodeBinding(toAlias)

    val leftPresent = tablePresent(leftAlias)
    val rightPresent = tablePresent(rightAlias)

    if !leftPresent && rightPresent then
      // Attach through the already-bound right endpoint, then join left.
      val edgeOn =
        if outgoing then
          joinEquals(
            keySourceCols(edgeType, edgeType.toKey, edgeDs),
            relAlias,
            idSourceCols(toB),
            toAlias
          )
        else
          joinEquals(
            keySourceCols(edgeType, edgeType.fromKey, edgeDs),
            relAlias,
            idSourceCols(fromB),
            fromAlias
          )
      addTable(edgeDs.qualifiedTable, relAlias, joinType, edgeOn)
      val leftOn =
        if outgoing then
          joinEquals(
            idSourceCols(fromB),
            fromAlias,
            keySourceCols(edgeType, edgeType.fromKey, edgeDs),
            relAlias
          )
        else
          joinEquals(
            idSourceCols(toB),
            toAlias,
            keySourceCols(edgeType, edgeType.toKey, edgeDs),
            relAlias
          )
      addTable(nodeBinding(leftAlias).ds.qualifiedTable, leftAlias, joinType, leftOn)
    else
      if !leftPresent then
        addTable(nodeBinding(leftAlias).ds.qualifiedTable, leftAlias, joinType, Nil)

      val edgeOn =
        if outgoing then
          joinEquals(
            idSourceCols(fromB),
            fromAlias,
            keySourceCols(edgeType, edgeType.fromKey, edgeDs),
            relAlias
          )
        else
          joinEquals(
            idSourceCols(toB),
            toAlias,
            keySourceCols(edgeType, edgeType.toKey, edgeDs),
            relAlias
          )
      addTable(edgeDs.qualifiedTable, relAlias, joinType, edgeOn)

      if !tablePresent(rightAlias) then
        val rightOn =
          if outgoing then
            joinEquals(
              keySourceCols(edgeType, edgeType.toKey, edgeDs),
              relAlias,
              idSourceCols(toB),
              toAlias
            )
          else
            joinEquals(
              keySourceCols(edgeType, edgeType.fromKey, edgeDs),
              relAlias,
              idSourceCols(fromB),
              fromAlias
            )
        addTable(nodeBinding(rightAlias).ds.qualifiedTable, rightAlias, joinType, rightOn)
      else
        val linkOn =
          if outgoing then
            joinEquals(
              keySourceCols(edgeType, edgeType.toKey, edgeDs),
              relAlias,
              idSourceCols(toB),
              toAlias
            )
          else
            joinEquals(
              keySourceCols(edgeType, edgeType.fromKey, edgeDs),
              relAlias,
              idSourceCols(fromB),
              fromAlias
            )
        val last = joins.last
        joins = joins.init :+ last.copy(on = last.on ++ linkOn)

    right.properties.foreach: props =>
      pendingOn ++= propertyMapPreds(rightAlias, nodeBinding(rightAlias).nodeType, props)
    rel.properties.foreach(_ => fail("Relationship property maps are not supported yet"))
    rightAlias

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

  private def addTable(
      table: String,
      alias: String,
      joinType: String,
      on: List[String]
  ): Unit =
    if tablePresent(alias) then ()
    else if fromItem.isEmpty then fromItem = Some(FromItem(table, alias))
    else if on.isEmpty then joins :+= JoinItem("CROSS JOIN", table, alias, Nil)
    else joins :+= JoinItem(joinType, table, alias, on)

  private def tablePresent(alias: String): Boolean =
    fromItem.exists(_.alias == alias) || joins.exists(_.alias == alias)

  private def nodeBinding(alias: String): Binding.Node =
    bindings.get(alias) match
      case Some(n: Binding.Node) => n
      case Some(_) => fail(s"Variable '$alias' is not a node")
      case None    => fail(s"Unbound variable: $alias")

  private def freshAnon(prefix: String): String =
    anonSeq += 1
    s"_$prefix$anonSeq"

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

  private def joinEquals(
      leftCols: List[String],
      leftAlias: String,
      rightCols: List[String],
      rightAlias: String
  ): List[String] =
    if leftCols.size != rightCols.size then
      fail(s"Join key arity mismatch: $leftCols vs $rightCols")
    leftCols.zip(rightCols).map { case (l, r) =>
      s"${qual(leftAlias, l)} = ${qual(rightAlias, r)}"
    }

  private def propertyMapPreds(
      alias: String,
      nodeType: NodeType,
      props: MapLiteral
  ): List[String] =
    props.entries.map { case (key, value) =>
      val col = nodeColumn(nodeType, key).getOrElse(
        fail(s"Unknown property '$key' on node label ${nodeType.label}")
      )
      s"${qual(alias, col)} = ${exprSql(value)}"
    }

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

  // --- Expressions ------------------------------------------------------------

  private def exprSql(expr: Expr): String =
    expr match
      case Variable(name) =>
        bindings.get(name) match
          case Some(n: Binding.Node) =>
            // bare node var in scalar context is not valid alone; used in comparisons
            qual(n.alias, idSourceCols(n).head)
          case Some(r: Binding.Rel) =>
            qual(r.alias, keySourceCols(r.edgeType, r.edgeType.id, r.ds).headOption.getOrElse(
              fail(s"Relationship '$name' has no id columns")
            ))
          case None => fail(s"Unbound variable: $name")

      case Property(Variable(name), key) =>
        bindings.get(name) match
          case Some(n: Binding.Node) =>
            val col = nodeColumn(n.nodeType, key).getOrElse(
              fail(s"Unknown property '$key' on node '${n.nodeType.label}'")
            )
            qual(n.alias, col)
          case Some(r: Binding.Rel) =>
            val col = edgeColumn(r.edgeType, r.ds, key).getOrElse(
              fail(s"Unknown property '$key' on relationship '${r.edgeType.label}'")
            )
            qual(r.alias, col)
          case None => fail(s"Unbound variable: $name")

      case Property(_, _) => fail("Nested property access is not supported")

      case StringLit(v)   => literalString(v)
      case IntegerLit(v)  => v.toString
      case FloatLit(v)    => v.toString
      case BooleanLit(v)  => if v then "TRUE" else "FALSE"
      case NullLit        => "NULL"
      case Parameter(n)   => s"{$n}"

      case Not(e)         => s"(NOT ${exprSql(e)})"
      case And(l, r)      => s"(${exprSql(l)} AND ${exprSql(r)})"
      case Or(l, r)       => s"(${exprSql(l)} OR ${exprSql(r)})"
      case Xor(l, r)      => s"(${exprSql(l)} XOR ${exprSql(r)})"

      case Comparison(l, op, r) =>
        (l, r) match
          case (vl: Variable, vr: Variable) =>
            nodeIdComparison(vl.name, vr.name, op)
          case _ =>
            s"${exprSql(l)} ${cmpOp(op)} ${exprSql(r)}"

      case Add(l, r)       => s"(${exprSql(l)} + ${exprSql(r)})"
      case Subtract(l, r)  => s"(${exprSql(l)} - ${exprSql(r)})"
      case Multiply(l, r)  => s"(${exprSql(l)} * ${exprSql(r)})"
      case Divide(l, r)    => s"(${exprSql(l)} / ${exprSql(r)})"
      case Modulo(l, r)    => s"(${exprSql(l)} % ${exprSql(r)})"
      case Pow(l, r)       => s"pow(${exprSql(l)}, ${exprSql(r)})"
      case UnaryPlus(e)    => s"(+${exprSql(e)})"
      case UnaryMinus(e)   => s"(-${exprSql(e)})"

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
        if labels.forall(_ == n.nodeType.label) then "TRUE"
        else "FALSE"
      case HasLabels(_, _) => fail("Unsupported label predicate")

      case MapLiteral(_) | ContainerIndex(_, _) | Star =>
        fail(s"Unsupported expression in SQL conversion: $expr")

  private def nodeIdComparison(left: String, right: String, op: CmpOp): String =
    val l = nodeBinding(left)
    val r = nodeBinding(right)
    val lcols = idSourceCols(l)
    val rcols = idSourceCols(r)
    if lcols.size != rcols.size then fail(s"Cannot compare nodes $left and $right: id arity mismatch")
    val parts = lcols.zip(rcols).map { case (lc, rc) =>
      s"${qual(l.alias, lc)} ${cmpOp(op)} ${qual(r.alias, rc)}"
    }
    if parts.size == 1 then parts.head
    else parts.mkString("(", s" AND ", ")")

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

  // --- SELECT / render --------------------------------------------------------

  private def render(ret: Return): String =
    val selectItems =
      if ret.items.size == 1 && ret.items.head.expr == Star then
        bindings.keys.toList.sorted.flatMap(expandVariable)
      else ret.items.flatMap(projectReturnItem)

    if selectItems.isEmpty then fail("RETURN produced no columns")

    val sb = new StringBuilder
    sb.append("SELECT")
    if ret.distinct then sb.append(" DISTINCT")
    sb.append("\n  ")
    sb.append(selectItems.mkString(",\n  "))
    sb.append("\nFROM ")
    val from = fromItem.getOrElse(fail("No FROM clause generated"))
    sb.append(s"${from.table} AS ${qid(from.alias)}")
    joins.foreach: j =>
      sb.append(s"\n${j.joinType} ${j.table} AS ${qid(j.alias)}")
      if j.on.nonEmpty then sb.append(s" ON ${j.on.mkString(" AND ")}")
    if wherePreds.nonEmpty then
      sb.append("\nWHERE ")
      sb.append(wherePreds.mkString(" AND "))
    if ret.orderBy.nonEmpty then
      sb.append("\nORDER BY ")
      sb.append(
        ret.orderBy
          .map(si => s"${exprSql(si.expr)}${if si.ascending then "" else " DESC"}")
          .mkString(", ")
      )
    ret.skip.foreach(s => sb.append(s"\nOFFSET ${exprSql(s)}"))
    ret.limit.foreach(l => sb.append(s"\nLIMIT ${exprSql(l)}"))
    sb.toString

  private def projectReturnItem(item: ReturnItem): List[String] =
    item.expr match
      case Variable(name) =>
        val cols = expandVariable(name)
        item.alias match
          case None => cols
          case Some(a) if cols.size == 1 =>
            List(cols.head.replaceAll(" AS `[^`]+`$", s" AS ${qid(a)}"))
          case Some(_) =>
            fail(s"Cannot alias expanded variable '$name' to a single name")
      case Star =>
        fail("RETURN * mixed with other items is not supported")
      case other =>
        val sql = exprSql(other)
        val alias = item.alias.getOrElse(defaultAlias(other))
        List(s"$sql AS ${qid(alias)}")

  private def defaultAlias(expr: Expr): String =
    expr match
      case Property(Variable(n), key) => s"$n.$key"
      case Variable(n)                => n
      case _                          => "expr"

  private def expandVariable(name: String): List[String] =
    bindings.get(name) match
      case Some(n: Binding.Node) =>
        val fields =
          if n.nodeType.attributes.nonEmpty then n.nodeType.attributes.map(_.name)
          else n.nodeType.id.map(_.name)
        fields.map: field =>
          val col = nodeColumn(n.nodeType, field).getOrElse(
            fail(s"No SQL column for '${n.nodeType.label}.$field'")
          )
          s"${qual(n.alias, col)} AS `${n.alias}.$field`"
      case Some(r: Binding.Rel) =>
        val fields =
          (r.edgeType.id ++ r.edgeType.fromKey ++ r.edgeType.toKey ++ r.edgeType.attributes)
            .map(_.name)
            .distinct
        fields.map: field =>
          val col = edgeColumn(r.edgeType, r.ds, field).getOrElse(
            fail(s"No SQL column for relationship field '$field'")
          )
          s"${qual(r.alias, col)} AS `${r.alias}.$field`"
      case None => fail(s"Unbound variable in RETURN: $name")
