package cypher2sql.schema

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TableSizeReaderSpec extends AnyFlatSpec with Matchers:

  "TableSizeReader" should "parse table sizes into a name→rows map" in:
    val json =
      """
      [
        {"schema": "puppy", "table_name": "people_agg", "rows": 100},
        {"schema": "puppy", "table_name": "tag_all", "rows": 42}
      ]
      """
    val sizes = TableSizeReader.readString(json) match
      case Right(s)  => s
      case Left(err) => fail(err)

    sizes.byTableName shouldBe Map(
      "people_agg" -> 100L,
      "tag_all" -> 42L
    )
    sizes.rows("people_agg") shouldBe Some(100L)
    sizes.rows("puppy", "tag_all") shouldBe Some(42L)
    sizes.rows("missing") shouldBe None
