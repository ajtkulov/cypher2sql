package cypher2sql.parser

import cypher2sql.ast.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CypherParserSpec extends AnyFlatSpec with Matchers:

  private def parseOk(cypher: String): Query =
    CypherParser.parse(cypher) match
      case Right(q)  => q
      case Left(err) => fail(err)

  "CypherParser" should "parse a simple MATCH RETURN" in:
    val q = parseOk(
      """
        MATCH (n:Person)
        RETURN n.name
      """
    )
    q.clauses should have size 2
    q.clauses(0) shouldBe a[Match]
    q.clauses(1) shouldBe a[Return]

    val m = q.clauses(0).asInstanceOf[Match]
    m.optional shouldBe false
    m.pattern.parts should have size 1
    m.pattern.parts.head.element shouldBe
      NodePattern(Some("n"), List("Person"), None)

    val r = q.clauses(1).asInstanceOf[Return]
    r.items shouldBe List(ReturnItem(Property(Variable("n"), "name"), None))

  it should "parse OPTIONAL MATCH with WHERE and relationship" in:
    val q = parseOk(
      """
        OPTIONAL MATCH (a:Person)-[r:KNOWS]->(b:Person)
        WHERE a.age > 30 AND b.name STARTS WITH 'A'
        RETURN a, b.name AS friend
        ORDER BY a.age DESC
        LIMIT 10
      """
    )
    val m = q.clauses(0).asInstanceOf[Match]
    m.optional shouldBe true
    m.where shouldBe defined

    m.pattern.parts.head.element match
      case PathPattern(start, chain) =>
        start shouldBe NodePattern(Some("a"), List("Person"), None)
        chain should have size 1
        val (rel, end) = chain.head
        rel.variable shouldBe Some("r")
        rel.types shouldBe List("KNOWS")
        rel.direction shouldBe Direction.Outgoing
        end shouldBe NodePattern(Some("b"), List("Person"), None)
      case other => fail(s"expected PathPattern, got $other")

    val ret = q.clauses(1).asInstanceOf[Return]
    ret.items should have size 2
    ret.items(1) shouldBe ReturnItem(Property(Variable("b"), "name"), Some("friend"))
    ret.orderBy shouldBe List(SortItem(Property(Variable("a"), "age"), ascending = false))
    ret.limit shouldBe Some(IntegerLit(10))

  it should "parse WITH, properties map, and functions" in:
    val q = parseOk(
      """
        MATCH (n:Person {name: 'Ada'})
        WITH n, count(n) AS c
        WHERE c > 0
        RETURN DISTINCT n
      """
    )
    q.clauses should have size 3
    val m = q.clauses(0).asInstanceOf[Match]
    m.pattern.parts.head.element match
      case NodePattern(Some("n"), List("Person"), Some(MapLiteral(entries))) =>
        entries shouldBe List("name" -> StringLit("Ada"))
      case other => fail(s"unexpected pattern: $other")

    val w = q.clauses(1).asInstanceOf[With]
    w.items(1) shouldBe
      ReturnItem(FunctionInvocation("count", distinct = false, List(Variable("n"))), Some("c"))
    w.where shouldBe Some(Comparison(Variable("c"), CmpOp.Gt, IntegerLit(0)))

    val r = q.clauses(2).asInstanceOf[Return]
    r.distinct shouldBe true

  it should "parse undirected and variable-length relationships" in:
    val q = parseOk("MATCH (a)-[:KNOWS|LIKES*1..3]-(b) RETURN *")
    val m = q.clauses(0).asInstanceOf[Match]
    m.pattern.parts.head.element match
      case PathPattern(_, List((rel, _))) =>
        rel.direction shouldBe Direction.Both
        rel.types shouldBe List("KNOWS", "LIKES")
        rel.length shouldBe Some(Range(Some(1), Some(3)))
      case other => fail(s"unexpected: $other")

    q.clauses(1).asInstanceOf[Return].items shouldBe List(ReturnItem(Star, None))

  it should "return a clear error on invalid input" in:
    CypherParser.parse("MATCH (n RETURN n") match
      case Left(err) => err should include("Parse error")
      case Right(q)  => fail(s"expected failure, got $q")
