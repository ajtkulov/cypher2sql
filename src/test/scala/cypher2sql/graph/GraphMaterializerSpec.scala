package cypher2sql.graph

import cypher2sql.mapping.CsvGraphMapping
import cypher2sql.schema.SchemaReader
import cypher2sql.subgraph.SubgraphExecutor
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

class GraphMaterializerSpec extends AnyFlatSpec with Matchers:

  private val schema = SchemaReader.readResource("schema.json") match
    case Right(s)  => s
    case Left(err) => fail(err)

  private val csvDir = Path.of(getClass.getResource("/csv/").toURI)

  private val mapping =
    CsvGraphMapping
      .load(csvDir.resolve("mapping_inn.json"))
      .flatMap(CsvGraphMapping.validateAgainstSchema(_, schema))
      .fold(e => fail(e), identity)

  "GraphMaterializer" should "materialize multi-entity rows into MappedGraph" in:
    val graph = GraphMaterializer.materialize(schema, mapping, csvDir).fold(e => fail(e), identity)
    graph.nodeTable("Person").map(_.rowCount()).getOrElse(0) should be >= 4
    graph.edgeTable("HAS_INN").map(_._2.rowCount()).getOrElse(0) should be >= 4

  it should "support Cypher over materialized result CSV" in:
    val graph = GraphMaterializer.materialize(schema, mapping, csvDir).fold(e => fail(e), identity)
    val sg = SubgraphExecutor
      .execute(
        """
          MATCH (p:Person)-[r:HAS_INN]->(i:INN)
          WHERE p.last_name = 'John'
          RETURN p, r, i
        """,
        graph
      )
      .fold(e => fail(e), identity)
    sg.nodes.exists(n => n.label == "Person" && n.properties.get("first_name").contains("Alice")) shouldBe true
    sg.relationships.exists(_.relType == "HAS_INN") shouldBe true
