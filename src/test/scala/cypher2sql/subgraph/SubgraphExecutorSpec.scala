package cypher2sql.subgraph

import cypher2sql.dataframe.CsvLoader
import cypher2sql.graph.MappedGraph
import cypher2sql.schema.SchemaReader
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

class SubgraphExecutorSpec extends AnyFlatSpec with Matchers:

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

  "SubgraphExecutor" should "filter nodes offline from CSV" in:
    val sg = SubgraphExecutor
      .execute(
        """
          MATCH (p:Person)
          WHERE p.last_name = 'John'
          RETURN p
        """,
        graph
      )
      .fold(e => fail(e), identity)
    sg.nodes.map(_.properties("first_name")).toSet shouldBe Set("Alice", "Carol")
    sg.relationships shouldBe empty

  it should "return a hop subgraph for Person-CITIZENSHIP-Citizenship" in:
    val sg = SubgraphExecutor
      .execute(
        """
          MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship)
          WHERE p.last_name = 'John'
          RETURN p, r, c
        """,
        graph
      )
      .fold(e => fail(e), identity)
    sg.nodes.exists(n => n.label == "Person" && n.properties("first_name") == "Alice") shouldBe true
    sg.nodes.exists(_.label == "Citizenship") shouldBe true
    sg.relationships.exists(_.relType == "CITIZENSHIP") shouldBe true

  it should "dedupe nodes across multiple matches" in:
    val sg = SubgraphExecutor
      .execute(
        """
          MATCH (p:Person)-[r:CITIZENSHIP]->(c:Citizenship)
          WHERE p.first_name = 'Alice'
          RETURN p, r, c
        """,
        graph
      )
      .fold(e => fail(e), identity)
    sg.nodes.count(n => n.label == "Person" && n.properties("person_hash") == "p1") shouldBe 1

  it should "reject WITH" in:
    SubgraphExecutor.execute("MATCH (p:Person) WITH p RETURN p", graph) match
      case Left(err) => err should include("WITH")
      case Right(_)  => fail("expected error")

  it should "reject OPTIONAL MATCH" in:
    SubgraphExecutor.execute("OPTIONAL MATCH (p:Person) RETURN p", graph) match
      case Left(err) => err.toLowerCase should include("optional")
      case Right(_)  => fail("expected error")
