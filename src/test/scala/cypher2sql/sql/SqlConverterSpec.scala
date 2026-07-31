package cypher2sql.sql

import cypher2sql.schema.{SchemaReader, TableSizeReader, TableSizes}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SqlConverterSpec extends AnyFlatSpec with Matchers:

  private val schema = SchemaReader.readResource("schema.json") match
    case Right(s)  => s
    case Left(err) => fail(err)

  private val tableSizes = TableSizeReader.readResource("table_size.json") match
    case Right(s)  => s
    case Left(err) => fail(err)

  private def sql(cypher: String, sizes: TableSizes = tableSizes): String =
    Cypher2Sql.convert(cypher, schema, sizes) match
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
            r.citizenship AS `c.citizenship`,
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

  it should "fuse first hop into one physical join with larger table first" in:
    val got = sql(
      """
        MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship)
        WHERE p.last_name = 'John'
        RETURN r
      """
    )
    "INNER JOIN".r.findAllIn(got).length shouldBe 1
    "step\\d+".r.findAllIn(got).toSet shouldBe Set("step1")
    // people_agg (215M) > people_citizenship (100M) ⇒ people_agg first
    normalize(got) should include(
      "FROM puppy.people_agg AS p INNER JOIN puppy.people_citizenship AS r ON r.person_hash = p.person_hash"
    )
    normalize(got) should include("WHERE p.last_name = 'John'")

  it should "fuse property-map filters into the first physical join" in:
    val got = sql(
      """
        MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship {name: 'RU'})
        RETURN p.person_hash, c.name
      """
    )
    normalize(got) shouldBe normalize(
      """
        WITH
        step1 AS (
          SELECT
            p.person_hash AS `p.person_hash`,
            p.first_name AS `p.first_name`,
            p.last_name AS `p.last_name`,
            p.middle_name AS `p.middle_name`,
            p.birth_date AS `p.birth_date`,
            r.person_hash AS `r.puppy_id_person_hash`,
            r.citizenship AS `r.puppy_id_citizenship`,
            r.person_hash AS `r.puppy_from_person_hash`,
            r.citizenship AS `r.puppy_to_citizenship`,
            r.citizenship AS `c.citizenship`,
            r.citizenship AS `c.name`
          FROM puppy.people_agg AS p
          INNER JOIN puppy.people_citizenship AS r ON r.person_hash = p.person_hash
          WHERE r.citizenship = 'RU'
        )
        SELECT
          prev.`p.person_hash` AS `p.person_hash`,
          prev.`c.name` AS `c.name`
        FROM step1 AS prev
      """
    )

  it should "put the larger edge table first when it outsizes the node table" in:
    val sizes = TableSizes(
      List(
        cypher2sql.schema.TableSizeEntry("puppy", "people_agg", 10),
        cypher2sql.schema.TableSizeEntry("puppy", "people_citizenship", 1000)
      )
    )
    val got = sql(
      """
        MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship)
        RETURN p.person_hash
      """,
      sizes
    )
    normalize(got) should include(
      "FROM puppy.people_citizenship AS r INNER JOIN puppy.people_agg AS p ON p.person_hash = r.person_hash"
    )

  it should "fail on unknown node label" in:
    Cypher2Sql.convert("MATCH (x:Unknown) RETURN x", schema) match
      case Left(err) => err should include("Unknown node label")
      case Right(s)  => fail(s"expected error, got $s")

  it should "retain node ids for repeated edge joins" in:
    val got = sql(
      """
        MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship)<-[r1:CITIZENSHIP]-(p1:Person)
        WHERE p1 <> p
          AND p.last_name <> p1.last_name
        RETURN DISTINCT p, c, p1
        LIMIT 10000
      """
    )
    normalize(got) should include("r1.citizenship = prev.`c.citizenship`")
