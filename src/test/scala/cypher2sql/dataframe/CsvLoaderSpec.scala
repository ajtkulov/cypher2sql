package cypher2sql.dataframe

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

class CsvLoaderSpec extends AnyFlatSpec with Matchers:

  private def resourcePath(name: String): Path =
    Path.of(getClass.getResource(s"/csv/$name").toURI)

  "CsvLoader" should "load a CSV with headers into a Tablesaw table" in:
    val t = CsvLoader.load(resourcePath("people_agg.csv")).fold(e => fail(e), identity)
    t.columnNames().contains("person_hash") shouldBe true
    t.columnNames().contains("last_name") shouldBe true
    t.rowCount() shouldBe 3

  it should "document Arrow as optional / not required for CSV" in:
    ArrowInterop.status.toLowerCase should include("arrow")
