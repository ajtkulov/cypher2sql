package cypher2sql.schema

/** One row from `table_size.json`. */
final case class TableSizeEntry(schema: String, tableName: String, rows: Long):
  def qualifiedName: String = s"$schema.$tableName"

/** Lookup of physical table row counts. */
final case class TableSizes(entries: List[TableSizeEntry]):
  /** Unqualified table name → rows (last entry wins on duplicates). */
  val byTableName: Map[String, Long] =
    entries.map(e => e.tableName -> e.rows).toMap

  /** `schema.table` → rows. */
  val byQualifiedName: Map[String, Long] =
    entries.map(e => e.qualifiedName -> e.rows).toMap

  def rows(tableName: String): Option[Long] = byTableName.get(tableName)

  def rows(schema: String, tableName: String): Option[Long] =
    byQualifiedName.get(s"$schema.$tableName")
