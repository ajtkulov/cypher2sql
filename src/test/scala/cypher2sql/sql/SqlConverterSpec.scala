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

  "Cypher2Sql" should "convert simple MATCH WHERE RETURN" in:
    val got = sql(
      """
        MATCH (p:Person)
        WHERE p.last_name = 'John'
        RETURN p.first_name, p.last_name
      """
    )
    normalize(got) shouldBe normalize(
      """
        SELECT
          p.first_name AS `p.first_name`,
          p.last_name AS `p.last_name`
        FROM puppy.people_agg AS p
        WHERE p.last_name = 'John'
      """
    )

  it should "convert a Person-CITIZENSHIP-Citizenship path and expand entities" in:
    val got = sql(
      """
        MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship)
        WHERE p.last_name = 'John'
        RETURN p, c.name
        LIMIT 100
      """
    )
    normalize(got) shouldBe normalize(
      """
        SELECT
          p.person_hash AS `p.person_hash`,
          p.first_name AS `p.first_name`,
          p.last_name AS `p.last_name`,
          p.middle_name AS `p.middle_name`,
          p.birth_date AS `p.birth_date`,
          c.citizenship AS `c.name`
        FROM puppy.people_agg AS p
        INNER JOIN puppy.people_citizenship AS r ON p.person_hash = r.person_hash
        INNER JOIN puppy.people_citizenship AS c ON r.citizenship = c.citizenship
        WHERE p.last_name = 'John'
        LIMIT 100
      """
    )

  it should "convert multi-MATCH reusing a bound person" in:
    val got = sql(
      """
        MATCH (p:Person)
        WHERE p.first_name = 'Smith'
        MATCH (p)-[r:CITIZENSHIP]->(c:Citizenship)
        RETURN p.person_hash, c.name
        ORDER BY p.person_hash
        LIMIT 10
      """
    )
    normalize(got) shouldBe normalize(
      """
        SELECT
          p.person_hash AS `p.person_hash`,
          c.citizenship AS `c.name`
        FROM puppy.people_agg AS p
        INNER JOIN puppy.people_citizenship AS r ON p.person_hash = r.person_hash
        INNER JOIN puppy.people_citizenship AS c ON r.citizenship = c.citizenship
        WHERE p.first_name = 'Smith'
        ORDER BY p.person_hash
        LIMIT 10
      """
    )

  it should "expand relationship variables in RETURN" in:
    val got = sql(
      """
        MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship)
        RETURN r
      """
    )
    normalize(got) should include("r.person_hash AS `r.puppy_id_person_hash`")
    normalize(got) should include("r.citizenship AS `r.puppy_to_citizenship`")

  it should "fail on unknown node label" in:
    Cypher2Sql.convert("MATCH (x:Unknown) RETURN x", schema) match
      case Left(err) => err should include("Unknown node label")
      case Right(s)  => fail(s"expected error, got $s")
