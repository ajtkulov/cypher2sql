package cypher2sql.sql

import cypher2sql.schema.SchemaReader
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SqlConverterSpec extends AnyFlatSpec with Matchers:

  private val schema = SchemaReader.readResource("schema.json") match
    case Right(s)  => s
    case Left(err) => fail(err)

  private def sql(cypher: String): String =
    Cypher2Sql.convert(cypher, schema) match
      case Right(s)  => s
      case Left(err) => fail(err)

  private def normalize(s: String): String =
    s.trim.replaceAll("\\s+", " ")

  "Cypher2Sql" should "seed a filtered person CTE" in:
    val got = sql(
      """
        MATCH (p:Person)
        WHERE p.last_name = 'John'
        RETURN p.first_name, p.last_name
      """
    )
    normalize(got) shouldBe normalize(
      """
        WITH
        step1 AS (
          SELECT
            person_hash AS `p.person_hash`,
            first_name AS `p.first_name`,
            last_name AS `p.last_name`,
            middle_name AS `p.middle_name`,
            birth_date AS `p.birth_date`
          FROM puppy.people_agg
          WHERE last_name = 'John'
        )
        SELECT
          prev.`p.first_name` AS `p.first_name`,
          prev.`p.last_name` AS `p.last_name`
        FROM step1 AS prev
      """
    )

  it should "join base table first, then previous CTE" in:
    val got = sql(
      """
        MATCH (p:Person)
        WHERE p.last_name = 'John'
        MATCH (p)-[r:CITIZENSHIP]->(c:Citizenship)
        RETURN p.person_hash, c.name
        LIMIT 100
      """
    )
    normalize(got) shouldBe normalize(
      """
        WITH
        step1 AS (
          SELECT
            person_hash AS `p.person_hash`,
            first_name AS `p.first_name`,
            last_name AS `p.last_name`,
            middle_name AS `p.middle_name`,
            birth_date AS `p.birth_date`
          FROM puppy.people_agg
          WHERE last_name = 'John'
        ),
        step2 AS (
          SELECT
            prev.`p.person_hash` AS `p.person_hash`,
            prev.`p.first_name` AS `p.first_name`,
            prev.`p.last_name` AS `p.last_name`,
            prev.`p.middle_name` AS `p.middle_name`,
            prev.`p.birth_date` AS `p.birth_date`,
            r.person_hash AS `r.puppy_id_person_hash`,
            r.citizenship AS `r.puppy_id_citizenship`,
            r.person_hash AS `r.puppy_from_person_hash`,
            r.citizenship AS `r.puppy_to_citizenship`,
            r.citizenship AS `c.name`
          FROM puppy.people_citizenship AS r
          INNER JOIN step1 AS prev ON r.person_hash = prev.`p.person_hash`
        )
        SELECT
          prev.`p.person_hash` AS `p.person_hash`,
          prev.`c.name` AS `c.name`
        FROM step2 AS prev
        LIMIT 100
      """
    )

  it should "use at most one base table join per CTE step" in:
    val got = sql(
      """
        MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship)
        WHERE p.last_name = 'John'
        RETURN r
      """
    )
    val joinCount = "INNER JOIN".r.findAllIn(got).length
    joinCount shouldBe 1
    got should include("FROM puppy.people_citizenship AS r")
    got should include("INNER JOIN step1 AS prev")
    normalize(got) should include("prev.`r.puppy_id_person_hash` AS `r.puppy_id_person_hash`")

  it should "fail on unknown node label" in:
    Cypher2Sql.convert("MATCH (x:Unknown) RETURN x", schema) match
      case Left(err) => err should include("Unknown node label")
      case Right(s)  => fail(s"expected error, got $s")
