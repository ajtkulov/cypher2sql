package cypher2sql.dataframe

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

final case class FileInspectSummary(
    path: Path,
    fileName: String,
    delimiter: Char,
    headers: List[String],
    rowCount: Long,
    samples: List[List[String]]
):
  def format: String =
    val delimLabel = if delimiter == '\t' then "TAB" else delimiter.toString
    val sb = new StringBuilder
    sb.append(s"$fileName\n")
    sb.append(s"  delimiter: $delimLabel\n")
    sb.append(s"  rows: $rowCount\n")
    sb.append(s"  headers (${headers.size}): ${headers.mkString(", ")}\n")
    if samples.nonEmpty then
      sb.append("  samples:\n")
      samples.zipWithIndex.foreach { case (row, i) =>
        sb.append(s"    [$i] ${row.mkString(" | ")}\n")
      }
    sb.toString

object CsvInspect:
  def inspectDir(
      dataDir: Path,
      sampleSize: Int = 3
  ): Either[String, List[FileInspectSummary]] =
    if dataDir == null || !Files.isDirectory(dataDir) then
      Left(s"Data directory not found: $dataDir")
    else
      val files =
        Files
          .list(dataDir)
          .iterator()
          .asScala
          .filter(p => Files.isRegularFile(p) && isCsvLike(p.getFileName.toString))
          .toList
          .sortBy(_.getFileName.toString)
      if files.isEmpty then Right(Nil)
      else
        files.foldLeft[Either[String, List[FileInspectSummary]]](Right(Nil)):
          case (Left(e), _) => Left(e)
          case (Right(acc), path) =>
            inspectFile(path, sampleSize).map(acc :+ _)

  def inspectFile(path: Path, sampleSize: Int = 3): Either[String, FileInspectSummary] =
    if !Files.isRegularFile(path) then Left(s"CSV file not found: $path")
    else
      Try {
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList
        if lines.isEmpty then
          FileInspectSummary(path, path.getFileName.toString, ',', Nil, 0L, Nil)
        else
          val delimiter = detectDelimiter(lines.head)
          val headers = splitLine(lines.head, delimiter)
          val data = lines.drop(1).filter(_.nonEmpty)
          val samples = data.take(sampleSize).map(splitLine(_, delimiter))
          FileInspectSummary(
            path,
            path.getFileName.toString,
            delimiter,
            headers,
            data.size.toLong,
            samples
          )
      }.toEither.left.map(e => s"Failed to inspect $path: ${e.getMessage}")

  def formatReport(summaries: List[FileInspectSummary]): String =
    if summaries.isEmpty then "No CSV/TSV files found.\n"
    else summaries.map(_.format).mkString("\n")

  private def isCsvLike(name: String): Boolean =
    val lower = name.toLowerCase
    lower.endsWith(".csv") || lower.endsWith(".tsv")

  private def detectDelimiter(headerLine: String): Char =
    val tabs = headerLine.count(_ == '\t')
    val commas = headerLine.count(_ == ',')
    if tabs >= commas && tabs > 0 then '\t' else ','

  private def splitLine(line: String, delimiter: Char): List[String] =
    line.split(java.util.regex.Pattern.quote(delimiter.toString), -1).toList
