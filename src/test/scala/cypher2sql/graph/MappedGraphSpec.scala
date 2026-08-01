package cypher2sql.graph

import cypher2sql.dataframe.CsvLoader
import cypher2sql.schema.SchemaReader
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

class MappedGraphSpec extends AnyFlatSpec with Matchers:

  private val schema = SchemaReader.readResource("schema.json") match
    case Right(s)  => s
    case Left(err) => fail(err)

  private def csv(name: String) =
    CsvLoader.load(Path.of(getClass.getResource(s"/csv/$name").toURI)).fold(e => fail(e), identity)

  private val graph = MappedGraph
    .bind(
      schema,
      Map(
        "puppy.people_agg" -> csv("people_agg.csv"),
        "puppy.people_citizenship" -> csv("people_citizenship.csv")
      )
    )
    .fold(e => fail(e), identity)

  "MappedGraph" should "bind Person to people_agg" in:
    graph.nodeTable("Person").isRight shouldBe true
    graph.nodeTable("Person").toOption.get.rowCount() shouldBe 3

  it should "error on missing binding" in:
    val g = MappedGraph.bind(schema, Map.empty).fold(e => fail(e), identity)
    g.nodeTable("Person") match
      case Left(err) => err should include("Missing CSV binding")
      case Right(_)  => fail("expected missing binding")

  it should "project Person last_name via mappedField" in:
    val nt = schema.requireNode("Person")
    graph.nodeSourceCol(nt, "last_name") shouldBe Right("last_name")

  it should "share one table for co-located Citizenship and CITIZENSHIP" in:
    val nodeT = graph.nodeTable("Citizenship").fold(e => fail(e), identity)
    val (_, edgeT) = graph.edgeTable("CITIZENSHIP").fold(e => fail(e), identity)
    nodeT should be theSameInstanceAs edgeT

  it should "map HAS_INN-style keys for CITIZENSHIP" in:
    val et = schema.requireEdge("CITIZENSHIP")
    graph.edgeSourceCol(et, "puppy_from_person_hash") shouldBe Right("person_hash")
    graph.edgeSourceCol(et, "puppy_to_citizenship") shouldBe Right("citizenship")
