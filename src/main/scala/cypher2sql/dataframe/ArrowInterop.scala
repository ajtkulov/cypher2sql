package cypher2sql.dataframe

/**
 * Optional Apache Arrow interchange (not wired in v1).
 * Enable arrow-vector deps in build.sbt, then implement export/import here.
 * Default CSV path uses Tablesaw only — see [[CsvLoader]].
 */
object ArrowInterop:
  def status: String =
    "Arrow interchange is optional and not implemented; use Tablesaw via CsvLoader"
