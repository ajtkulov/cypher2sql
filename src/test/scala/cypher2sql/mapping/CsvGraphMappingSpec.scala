package cypher2sql.mapping

import cypher2sql.schema.SchemaReader
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

class CsvGraphMappingSpec extends AnyFlatSpec with Matchers:

  private val schema = SchemaReader.readResource("schema.json") match
    case Right(s)  => s
    case Left(err) => fail(err)

  private def resource(name: String): Path =
    Path.of(getClass.getResource(s"/csv/$name").toURI)

  "CsvGraphMapping" should "load a valid mapping JSON" in:
    val m = CsvGraphMapping.load(resource("mapping_inn.json")).fold(e => fail(e), identity)
    m.files.size shouldBe 1
    m.files.head.nodes.map(_.label).toSet shouldBe Set("Person", "INN")

  it should "reject invalid JSON" in:
    val bad = Files.createTempFile("bad-map", ".json")
    Files.writeString(bad, "{not json")
    CsvGraphMapping.load(bad) match
      case Left(err) => err should include("Invalid mapping JSON")
      case Right(_)  => fail("expected error")

  it should "reject unknown labels against schema" in:
    val m = CsvGraphMapping.load(resource("mapping_inn.json")).fold(e => fail(e), identity)
    val twisted = m.copy(files =
      m.files.map(f => f.copy(nodes = f.nodes.map(n => n.copy(label = "NoSuchLabel"))))
    )
    CsvGraphMapping.validateAgainstSchema(twisted, schema) match
      case Left(err) => err should include("Unknown node label")
      case Right(_)  => fail("expected error")
