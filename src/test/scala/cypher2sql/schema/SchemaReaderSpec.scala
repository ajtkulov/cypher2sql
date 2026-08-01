package cypher2sql.schema

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SchemaReaderSpec extends AnyFlatSpec with Matchers:

  "SchemaReader" should "load PuppyGraph schema.json into a model" in:
    val schema = SchemaReader.readResource("schema.json") match
      case Right(s)  => s
      case Left(err) => fail(err)

    schema.catalogs.map(_.name) shouldBe List("clickhouse_data")
    schema.catalogs.head.typeName shouldBe "clickhouse"
    schema.catalogs.head.jdbc.flatMap(_.jdbcUri) shouldBe
      Some("jdbc:ch://172.17.0.1:8123")

    schema.nodes.map(_.label) shouldBe List("Person", "Citizenship", "INN")
    schema.edges.map(_.label) shouldBe List("CITIZENSHIP", "HAS_INN")

    val person = schema.requireNode("Person")
    person.id.map(_.name) shouldBe List("person_hash")
    person.attributes.map(_.name) should contain allOf ("first_name", "last_name")
    person.table.map(_.qualifiedTable) shouldBe Some("puppy.people_agg")
    person.table.flatMap(_.sourceFor("first_name")) shouldBe Some("first_name")

    val citizenship = schema.requireNode("Citizenship")
    citizenship.id.map(_.name) shouldBe List("citizenship")
    citizenship.attributes.map(_.name) shouldBe List("name")
    citizenship.table.map(_.table) shouldBe Some("people_citizenship")

    val rel = schema.requireEdge("CITIZENSHIP")
    rel.fromNodeLabel shouldBe "Person"
    rel.toNodeLabel shouldBe "Citizenship"
    rel.fromKey.map(_.name) shouldBe List("puppy_from_person_hash")
    rel.toKey.map(_.name) shouldBe List("puppy_to_citizenship")
    rel.table.map(_.qualifiedTable) shouldBe Some("puppy.people_citizenship")
    rel.table.flatMap(_.sourceFor("puppy_from_person_hash")) shouldBe
      Some("person_hash")

    schema.node("Missing") shouldBe None
    schema.edge("Missing") shouldBe None

  it should "parse a minimal inline schema" in:
    val json =
      """
      {
        "catalog": [{"name": "c", "type": "clickhouse"}],
        "node": [{
          "label": "Person",
          "id": [{"name": "id", "type": "STRING"}],
          "attribute": [{"name": "name", "type": "STRING"}],
          "dataSourceGroup": {
            "externalDataSource": {
              "enabled": true,
              "catalog": "c",
              "schema": "s",
              "table": "people",
              "mappedField": [
                {"sourceFieldName": "id", "targetFieldName": "id"},
                {"sourceFieldName": "nm", "targetFieldName": "name"}
              ]
            }
          }
        }],
        "edge": []
      }
      """
    val schema = SchemaReader.readString(json) match
      case Right(s)  => s
      case Left(err) => fail(err)

    schema.requireNode("Person").table.map(_.catalogPath) shouldBe
      Some("c.s.people")
