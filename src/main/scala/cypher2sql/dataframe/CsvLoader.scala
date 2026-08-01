package cypher2sql.dataframe

import tech.tablesaw.api.{ColumnType, StringColumn, Table}
import tech.tablesaw.io.csv.CsvReadOptions

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Load CSV/TSV files into Tablesaw tables (DataFrame-like). No Spark. */
object CsvLoader:
  def load(path: Path): Either[String, Table] =
    load(path, delimiter = ',')

  def load(path: Path, delimiter: Char): Either[String, Table] =
    if path == null then Left("CSV path is null")
    else if !Files.isRegularFile(path) then Left(s"CSV file not found: $path")
    else
      val opts = CsvReadOptions
        .builder(path.toFile)
        .header(true)
        .separator(delimiter)
        .build()
      Try {
        val t = Table.read().usingOptions(opts)
        normalizeStringColumns(t)
      }.toEither.left.map(e => s"Failed to load CSV $path: ${e.getMessage}")

  def load(path: String): Either[String, Table] =
    load(Path.of(path))

  def load(path: String, delimiter: Char): Either[String, Table] =
    load(Path.of(path), delimiter)

  private def normalizeStringColumns(t: Table): Table =
    t.columnNames().asScala.toList.foreach: name =>
      val col = t.column(name)
      if col.`type`() != ColumnType.STRING then
        val values =
          (0 until t.rowCount()).map: i =>
            Option(t.get(i, t.columnIndex(name))).map(_.toString).getOrElse("")
        t.replaceColumn(name, StringColumn.create(name, values*))
    t
