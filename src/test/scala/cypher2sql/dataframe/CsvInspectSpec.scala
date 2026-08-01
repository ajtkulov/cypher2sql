package cypher2sql.dataframe

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

class CsvInspectSpec extends AnyFlatSpec with Matchers:

  private val tmpDir: Path =
    val dir = Files.createTempDirectory("csv-inspect-")
    val tsv = dir.resolve("sample.inn.csv")
    Files.writeString(
      tsv,
      "p.person_hash\tp.first_name\tp.last_name\ni.innCode\tx\ty\n"
    )
    dir

  "CsvInspect" should "list csv files and detect tab delimiter" in:
    val summaries = CsvInspect.inspectDir(tmpDir).fold(e => fail(e), identity)
    summaries.size shouldBe 1
    summaries.head.delimiter shouldBe '\t'
    summaries.head.headers should contain("p.person_hash")
    summaries.head.rowCount shouldBe 1

  it should "error on missing data directory" in:
    CsvInspect.inspectDir(Path.of("/no/such/data/dir")) match
      case Left(err) => err should include("Data directory not found")
      case Right(_)  => fail("expected error")
