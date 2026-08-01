package cypher2sql.dataframe

import tech.tablesaw.api.{ColumnType, StringColumn, Table}
import tech.tablesaw.io.csv.CsvReadOptions

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Load CSV files into Tablesaw tables (DataFrame-like). No Spark. */
object CsvLoader:
  def load(path: Path): Either[String, Table] =
    val opts = CsvReadOptions.builder(path.toFile).header(true).build()
    Try {
      val t = Table.read().usingOptions(opts)
      // Normalize all columns to string for Cypher property maps.
      t.columnNames().asScala.toList.foreach: name =>
        val col = t.column(name)
        if col.`type`() != ColumnType.STRING then
          val values =
            (0 until t.rowCount()).map: i =>
              Option(t.get(i, t.columnIndex(name))).map(_.toString).getOrElse("")
          t.replaceColumn(name, StringColumn.create(name, values*))
      t
    }.toEither.left.map(e => s"Failed to load CSV $path: ${e.getMessage}")

  def load(path: String): Either[String, Table] =
    load(Path.of(path))
