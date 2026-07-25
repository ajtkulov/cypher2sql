package cypher2sql.parser

import cypher2sql.ast.*

import scala.util.parsing.combinator.RegexParsers

object CypherParser extends RegexParsers:
  override def skipWhitespace: Boolean = true
  override val whiteSpace = """(\s|//.*)+""".r

  private val reserved: Set[String] = Set(
    "and", "as", "asc", "ascending", "by", "contains", "desc", "descending",
    "distinct", "ends", "false", "in", "is", "limit", "match", "not", "null",
    "optional", "or", "order", "return", "skip", "starts", "true", "where",
    "with", "xor"
  )

  def parse(input: String): Either[String, Query] =
    parseAll(query, input) match
      case Success(result, _) => Right(result)
      case failure: NoSuccess =>
        Left(
          s"Parse error at line ${failure.next.pos.line}, column ${failure.next.pos.column}: ${failure.msg}"
        )

  private def query: Parser[Query] =
    rep1(clause) ^^ Query.apply

  private def clause: Parser[Clause] =
    matchClause | withClause | returnClause

  // --- Clauses ----------------------------------------------------------------

  private def matchClause: Parser[Match] =
    opt(kw("OPTIONAL")) ~ kw("MATCH") ~ pattern ~ opt(whereClause) ^^ {
      case optional ~ _ ~ pat ~ where => Match(optional.isDefined, pat, where)
    }

  private def whereClause: Parser[Expr] =
    kw("WHERE") ~> expression

  private def withClause: Parser[With] =
    kw("WITH") ~> opt(kw("DISTINCT")) ~ returnItems ~ opt(whereClause) ~
      opt(orderBy) ~ opt(skip) ~ opt(limit) ^^ {
        case distinct ~ items ~ where ~ order ~ sk ~ lim =>
          With(distinct.isDefined, items, where, order.getOrElse(Nil), sk, lim)
      }

  private def returnClause: Parser[Return] =
    kw("RETURN") ~> opt(kw("DISTINCT")) ~ returnItems ~
      opt(orderBy) ~ opt(skip) ~ opt(limit) ^^ {
        case distinct ~ items ~ order ~ sk ~ lim =>
          Return(distinct.isDefined, items, order.getOrElse(Nil), sk, lim)
      }

  private def returnItems: Parser[List[ReturnItem]] =
    "*" ^^^ List(ReturnItem(Star, None)) |
      rep1sep(returnItem, ",")

  private def returnItem: Parser[ReturnItem] =
    expression ~ opt(kw("AS") ~> symbolicName) ^^ {
      case expr ~ alias => ReturnItem(expr, alias)
    }

  private def orderBy: Parser[List[SortItem]] =
    kw("ORDER") ~> kw("BY") ~> rep1sep(sortItem, ",")

  private def sortItem: Parser[SortItem] =
    expression ~ opt(kw("ASC") | kw("ASCENDING") | kw("DESC") | kw("DESCENDING")) ^^ {
      case expr ~ Some(dir) =>
        SortItem(expr, !dir.equalsIgnoreCase("DESC") && !dir.equalsIgnoreCase("DESCENDING"))
      case expr ~ None =>
        SortItem(expr, ascending = true)
    }

  private def skip: Parser[Expr] = kw("SKIP") ~> expression
  private def limit: Parser[Expr] = kw("LIMIT") ~> expression

  // --- Patterns ---------------------------------------------------------------

  private def pattern: Parser[Pattern] =
    rep1sep(patternPart, ",") ^^ Pattern.apply

  private def patternPart: Parser[PatternPart] =
    opt(symbolicName <~ "=") ~ patternElement ^^ {
      case v ~ el => PatternPart(v, el)
    }

  private def patternElement: Parser[PatternElement] =
    nodePattern ~ rep(relationshipPattern ~ nodePattern) ^^ {
      case start ~ Nil => start
      case start ~ chain =>
        PathPattern(start, chain.map { case rel ~ node => (rel, node) })
    }

  private def nodePattern: Parser[NodePattern] =
    "(" ~> opt(symbolicName) ~ rep(":" ~> symbolicName) ~ opt(mapLiteral) <~ ")" ^^ {
      case v ~ labels ~ props => NodePattern(v, labels, props)
    }

  private def relationshipPattern: Parser[RelationshipPattern] =
    leftArrow ~ "-" ~ opt(relationshipDetail) ~ "-" ~ rightArrow ^^ {
      case left ~ _ ~ detail ~ _ ~ right =>
        val direction =
          (left, right) match
            case (true, false)  => Direction.Incoming
            case (false, true)  => Direction.Outgoing
            case _              => Direction.Both
        detail match
          case Some((v, types, length, props)) =>
            RelationshipPattern(v, types, length, props, direction)
          case None =>
            RelationshipPattern(None, Nil, None, None, direction)
    }

  private def leftArrow: Parser[Boolean] = opt("<") ^^ (_.isDefined)
  private def rightArrow: Parser[Boolean] = opt(">") ^^ (_.isDefined)

  private def relationshipDetail
      : Parser[(Option[String], List[String], Option[Range], Option[MapLiteral])] =
    "[" ~> opt(symbolicName) ~ opt(":" ~> rep1sep(symbolicName, "|")) ~
      opt(variableLength) ~ opt(mapLiteral) <~ "]" ^^ {
        case v ~ types ~ length ~ props =>
          (v, types.getOrElse(Nil), length, props)
      }

  private def variableLength: Parser[Range] =
    "*" ~> opt(integerLiteral) ~ opt(".." ~> opt(integerLiteral)) ^^ {
      case None ~ None            => Range(None, None)
      case Some(n) ~ None         => Range(Some(n.toInt), Some(n.toInt))
      case None ~ Some(max)       => Range(None, max.map(_.toInt))
      case Some(min) ~ Some(max)  => Range(Some(min.toInt), max.map(_.toInt))
    }

  // --- Expressions ------------------------------------------------------------

  private def expression: Parser[Expr] = orExpr

  private def orExpr: Parser[Expr] =
    xorExpr ~ rep(kw("OR") ~> xorExpr) ^^ {
      case first ~ rest => rest.foldLeft(first)(Or.apply)
    }

  private def xorExpr: Parser[Expr] =
    andExpr ~ rep(kw("XOR") ~> andExpr) ^^ {
      case first ~ rest => rest.foldLeft(first)(Xor.apply)
    }

  private def andExpr: Parser[Expr] =
    notExpr ~ rep(kw("AND") ~> notExpr) ^^ {
      case first ~ rest => rest.foldLeft(first)(And.apply)
    }

  private def notExpr: Parser[Expr] =
    rep(kw("NOT")) ~ comparisonExpr ^^ {
      case nots ~ expr => nots.foldLeft(expr)((e, _) => Not(e))
    }

  private def comparisonExpr: Parser[Expr] =
    addExpr ~ rep(predicateTail) ^^ {
      case first ~ tails =>
        tails.foldLeft(first) { (lhs, build) => build(lhs) }
    }

  private def predicateTail: Parser[Expr => Expr] =
    ("=" ~> addExpr) ^^ (rhs => (lhs: Expr) => Comparison(lhs, CmpOp.Eq, rhs)) |
      ("<>" ~> addExpr) ^^ (rhs => (lhs: Expr) => Comparison(lhs, CmpOp.Neq, rhs)) |
      ("<=" ~> addExpr) ^^ (rhs => (lhs: Expr) => Comparison(lhs, CmpOp.Lte, rhs)) |
      (">=" ~> addExpr) ^^ (rhs => (lhs: Expr) => Comparison(lhs, CmpOp.Gte, rhs)) |
      ("<" ~> addExpr) ^^ (rhs => (lhs: Expr) => Comparison(lhs, CmpOp.Lt, rhs)) |
      (">" ~> addExpr) ^^ (rhs => (lhs: Expr) => Comparison(lhs, CmpOp.Gt, rhs)) |
      (kw("STARTS") ~> kw("WITH") ~> addExpr) ^^ (rhs => (lhs: Expr) => StartsWith(lhs, rhs)) |
      (kw("ENDS") ~> kw("WITH") ~> addExpr) ^^ (rhs => (lhs: Expr) => EndsWith(lhs, rhs)) |
      (kw("CONTAINS") ~> addExpr) ^^ (rhs => (lhs: Expr) => Contains(lhs, rhs)) |
      ("=~" ~> addExpr) ^^ (rhs => (lhs: Expr) => RegexMatch(lhs, rhs)) |
      (kw("IN") ~> addExpr) ^^ (rhs => (lhs: Expr) => In(lhs, rhs)) |
      (kw("IS") ~> kw("NOT") ~> kw("NULL")) ^^^ ((lhs: Expr) => IsNotNull(lhs)) |
      (kw("IS") ~> kw("NULL")) ^^^ ((lhs: Expr) => IsNull(lhs))

  private def addExpr: Parser[Expr] =
    mulExpr ~ rep(("+" | "-") ~ mulExpr) ^^ {
      case first ~ rest =>
        rest.foldLeft(first) {
          case (lhs, "+" ~ rhs) => Add(lhs, rhs)
          case (lhs, "-" ~ rhs) => Subtract(lhs, rhs)
          case (lhs, op ~ _)    => sys.error(s"unexpected op $op")
        }
    }

  private def mulExpr: Parser[Expr] =
    powExpr ~ rep(("*" | "/" | "%") ~ powExpr) ^^ {
      case first ~ rest =>
        rest.foldLeft(first) {
          case (lhs, "*" ~ rhs) => Multiply(lhs, rhs)
          case (lhs, "/" ~ rhs) => Divide(lhs, rhs)
          case (lhs, "%" ~ rhs) => Modulo(lhs, rhs)
          case (lhs, op ~ _)    => sys.error(s"unexpected op $op")
        }
    }

  private def powExpr: Parser[Expr] =
    unaryExpr ~ opt("^" ~> unaryExpr) ^^ {
      case lhs ~ Some(rhs) => Pow(lhs, rhs)
      case lhs ~ None      => lhs
    }

  private def unaryExpr: Parser[Expr] =
    "+" ~> unaryExpr ^^ UnaryPlus.apply |
      "-" ~> unaryExpr ^^ UnaryMinus.apply |
      postfixExpr

  private def postfixExpr: Parser[Expr] =
    primary ~ rep(propertyLookup | indexLookup | labelLookup) ^^ {
      case first ~ ops => ops.foldLeft(first)((e, f) => f(e))
    }

  private def propertyLookup: Parser[Expr => Expr] =
    "." ~> symbolicName ^^ (key => (e: Expr) => Property(e, key))

  private def indexLookup: Parser[Expr => Expr] =
    "[" ~> expression <~ "]" ^^ (idx => (e: Expr) => ContainerIndex(e, idx))

  private def labelLookup: Parser[Expr => Expr] =
    rep1(":" ~> symbolicName) ^^ (labels => (e: Expr) => HasLabels(e, labels))

  private def primary: Parser[Expr] =
    literal |
      parameter |
      listLiteral |
      mapLiteral |
      "(" ~> expression <~ ")" |
      functionOrVariable

  private def functionOrVariable: Parser[Expr] =
    symbolicName ~ opt("(" ~> opt(kw("DISTINCT")) ~ repsep(expression, ",") <~ ")") ^^ {
      case name ~ Some(distinct ~ args) =>
        FunctionInvocation(name, distinct.isDefined, args)
      case name ~ None =>
        Variable(name)
    }

  private def listLiteral: Parser[ListLiteral] =
    "[" ~> repsep(expression, ",") <~ "]" ^^ ListLiteral.apply

  private def mapLiteral: Parser[MapLiteral] =
    "{" ~> repsep(mapEntry, ",") <~ "}" ^^ MapLiteral.apply

  private def mapEntry: Parser[(String, Expr)] =
    (symbolicName | stringLiteralValue) ~ (":" ~> expression) ^^ {
      case key ~ value => (key, value)
    }

  private def parameter: Parser[Parameter] =
    "$" ~> symbolicName ^^ Parameter.apply

  // --- Literals & names -------------------------------------------------------

  private def literal: Parser[Literal] =
    floatLiteral ^^ FloatLit.apply |
      integerLiteral ^^ IntegerLit.apply |
      stringLiteralValue ^^ StringLit.apply |
      kw("TRUE") ^^^ BooleanLit(true) |
      kw("FALSE") ^^^ BooleanLit(false) |
      kw("NULL") ^^^ NullLit

  private def integerLiteral: Parser[Long] =
    """0|[1-9][0-9]*""".r ^^ (_.toLong)

  private def floatLiteral: Parser[Double] =
    """(?:0|[1-9][0-9]*)\.[0-9]+(?:[eE][+-]?[0-9]+)?|[0-9]+[eE][+-]?[0-9]+""".r ^^ (_.toDouble)

  private def stringLiteralValue: Parser[String] =
    """'(?:\\'|[^'])*'""".r ^^ { s => unescape(s.substring(1, s.length - 1), '\'') } |
      """"(?:\\"|[^"])*"""".r ^^ { s => unescape(s.substring(1, s.length - 1), '"') }

  private def unescape(s: String, quote: Char): String =
    val q = quote.toString
    s.replace("\\" + q, q)
      .replace("\\n", "\n")
      .replace("\\t", "\t")
      .replace("\\\\", "\\")

  private def symbolicName: Parser[String] =
    """`(?:\\`|[^`])+`""".r ^^ { s => s.substring(1, s.length - 1).replace("\\`", "`") } |
      """[A-Za-z_][A-Za-z0-9_]*""".r ^? (
        { case name if !reserved.contains(name.toLowerCase) => name },
        name => s"reserved keyword '$name'"
      )

  private def kw(word: String): Parser[String] =
    s"(?i)${java.util.regex.Pattern.quote(word)}\\b".r
end CypherParser
