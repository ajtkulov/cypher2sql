package cypher2sql.ast

/** Intermediate representation for a parsed Cypher query. */
final case class Query(clauses: List[Clause])

sealed trait Clause

final case class Match(
    optional: Boolean,
    pattern: Pattern,
    where: Option[Expr]
) extends Clause

final case class With(
    distinct: Boolean,
    items: List[ReturnItem],
    where: Option[Expr],
    orderBy: List[SortItem],
    skip: Option[Expr],
    limit: Option[Expr]
) extends Clause

final case class Return(
    distinct: Boolean,
    items: List[ReturnItem],
    orderBy: List[SortItem],
    skip: Option[Expr],
    limit: Option[Expr]
) extends Clause

final case class ReturnItem(expr: Expr, alias: Option[String])
final case class SortItem(expr: Expr, ascending: Boolean)

final case class Pattern(parts: List[PatternPart])
final case class PatternPart(variable: Option[String], element: PatternElement)

sealed trait PatternElement

final case class NodePattern(
    variable: Option[String],
    labels: List[String],
    properties: Option[MapLiteral]
) extends PatternElement

/** A path: node (rel node)* */
final case class PathPattern(
    start: NodePattern,
    chain: List[(RelationshipPattern, NodePattern)]
) extends PatternElement

enum Direction:
  case Outgoing, Incoming, Both

final case class Range(min: Option[Int], max: Option[Int])

final case class RelationshipPattern(
    variable: Option[String],
    types: List[String],
    length: Option[Range],
    properties: Option[MapLiteral],
    direction: Direction
)

sealed trait Expr

final case class Variable(name: String) extends Expr
final case class Property(map: Expr, key: String) extends Expr
final case class Parameter(name: String) extends Expr
final case class FunctionInvocation(name: String, distinct: Boolean, args: List[Expr])
    extends Expr
final case class ListLiteral(elements: List[Expr]) extends Expr
final case class MapLiteral(entries: List[(String, Expr)]) extends Expr
final case class ContainerIndex(expr: Expr, index: Expr) extends Expr
final case class HasLabels(expr: Expr, labels: List[String]) extends Expr

sealed trait Literal extends Expr
final case class StringLit(value: String) extends Literal
final case class IntegerLit(value: Long) extends Literal
final case class FloatLit(value: Double) extends Literal
final case class BooleanLit(value: Boolean) extends Literal
case object NullLit extends Literal

final case class Not(expr: Expr) extends Expr
final case class Or(lhs: Expr, rhs: Expr) extends Expr
final case class Xor(lhs: Expr, rhs: Expr) extends Expr
final case class And(lhs: Expr, rhs: Expr) extends Expr

enum CmpOp:
  case Eq, Neq, Lt, Lte, Gt, Gte

final case class Comparison(lhs: Expr, op: CmpOp, rhs: Expr) extends Expr
final case class Add(lhs: Expr, rhs: Expr) extends Expr
final case class Subtract(lhs: Expr, rhs: Expr) extends Expr
final case class Multiply(lhs: Expr, rhs: Expr) extends Expr
final case class Divide(lhs: Expr, rhs: Expr) extends Expr
final case class Modulo(lhs: Expr, rhs: Expr) extends Expr
final case class Pow(lhs: Expr, rhs: Expr) extends Expr
final case class UnaryPlus(expr: Expr) extends Expr
final case class UnaryMinus(expr: Expr) extends Expr

final case class IsNull(expr: Expr) extends Expr
final case class IsNotNull(expr: Expr) extends Expr
final case class In(lhs: Expr, rhs: Expr) extends Expr
final case class StartsWith(lhs: Expr, rhs: Expr) extends Expr
final case class EndsWith(lhs: Expr, rhs: Expr) extends Expr
final case class Contains(lhs: Expr, rhs: Expr) extends Expr
final case class RegexMatch(lhs: Expr, rhs: Expr) extends Expr

/** Star projection in RETURN / WITH. */
case object Star extends Expr
