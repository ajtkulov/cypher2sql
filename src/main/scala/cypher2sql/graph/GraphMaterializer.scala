package cypher2sql.graph

import cypher2sql.dataframe.CsvLoader
import cypher2sql.mapping.{CsvGraphMapping, MappedFile, MappedNode}
import cypher2sql.schema.*
import tech.tablesaw.api.{StringColumn, Table}

import java.nio.file.Path
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Project mapped result CSVs into schema-bound Tablesaw tables for MappedGraph. */
object GraphMaterializer:

  def materialize(
      schema: GraphSchema,
      mapping: CsvGraphMapping,
      dataDir: Path
  ): Either[String, MappedGraph] =
    // label -> (sourceCol -> values by idKey)
    val nodeRows = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, Map[String, String]]]
    // edgeLabel -> list of physical column maps
    val edgeRows = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Map[String, String]]]

    mapping.files.foreach: file =>
      loadAndProject(schema, file, dataDir, nodeRows, edgeRows) match
        case Left(err) => return Left(err)
        case Right(_)  => ()

    for
      tables <- buildTables(schema, nodeRows, edgeRows)
      graph  <- MappedGraph.bind(schema, tables)
    yield graph

  private def loadAndProject(
      schema: GraphSchema,
      file: MappedFile,
      dataDir: Path,
      nodeRows: mutable.LinkedHashMap[String, mutable.LinkedHashMap[String, Map[String, String]]],
      edgeRows: mutable.LinkedHashMap[String, mutable.ArrayBuffer[Map[String, String]]]
  ): Either[String, Unit] =
    val path = dataDir.resolve(file.path)
    CsvLoader.load(path, file.delimiterChar).flatMap: table =>
      val colNames = table.columnNames().asScala.toSet
      val missing =
        file.nodes.flatMap(_.columns.values).filterNot(colNames.contains).distinct
      if missing.nonEmpty then
        Left(s"File ${file.path} missing columns: ${missing.mkString(", ")}")
      else
        val snapshots = snapshot(table)
        val nodesByRole = file.nodes.map(n => n.id -> n).toMap

        snapshots.foreach: row =>
          file.nodes.foreach: nodeMap =>
            projectNode(schema, nodeMap, row, nodeRows) match
              case Left(err) => return Left(err)
              case Right(_)  => ()

          file.relationships.foreach: rel =>
            val fromNode = nodesByRole(rel.from)
            val toNode = nodesByRole(rel.to)
            projectRel(schema, rel.`type`, fromNode, toNode, row, edgeRows) match
              case Left(err) => return Left(err)
              case Right(_)  => ()
        Right(())

  private def projectNode(
      schema: GraphSchema,
      nodeMap: MappedNode,
      csvRow: Map[String, String],
      nodeRows: mutable.LinkedHashMap[String, mutable.LinkedHashMap[String, Map[String, String]]]
  ): Either[String, Unit] =
    val nt = schema.node(nodeMap.label).getOrElse:
      return Left(s"Unknown node label: ${nodeMap.label}")
    val ds = nt.table.getOrElse:
      return Left(s"Node '${nt.label}' has no table mapping")

    // Build physical source column -> value using schema mapping
    val physical = mutable.LinkedHashMap.empty[String, String]
    val idParts = nt.id.map: field =>
      val csvCol = nodeMap.columns.getOrElse(
        field.name,
        return Left(s"Node '${nodeMap.id}' missing id column mapping for '${field.name}'")
      )
      val src = ds.sourceFor(field.name).getOrElse(field.name)
      val v = csvRow.getOrElse(csvCol, "")
      physical(src) = v
      v

    if idParts.forall(_.isEmpty) then return Right(()) // skip empty entities

    nodeMap.columns.foreach { case (graphField, csvCol) =>
      val src = ds.sourceFor(graphField).getOrElse(graphField)
      physical(src) = csvRow.getOrElse(csvCol, "")
    }

    val idKey = idParts.mkString("|")
    val bucket = nodeRows.getOrElseUpdate(nt.label, mutable.LinkedHashMap.empty)
    bucket.get(idKey) match
      case None => bucket(idKey) = physical.toMap
      case Some(_) => () // keep first
    Right(())

  private def projectRel(
      schema: GraphSchema,
      relType: String,
      fromNode: MappedNode,
      toNode: MappedNode,
      csvRow: Map[String, String],
      edgeRows: mutable.LinkedHashMap[String, mutable.ArrayBuffer[Map[String, String]]]
  ): Either[String, Unit] =
    val et = schema.edge(relType).getOrElse:
      return Left(s"Unknown relationship type: $relType")
    val ds = et.table.getOrElse:
      return Left(s"Relationship '$relType' has no table mapping")

    def endpointValues(
        nodeMap: MappedNode,
        keys: List[GraphField],
        expectedLabel: String
    ): Either[String, List[(String, String)]] =
      if nodeMap.label != expectedLabel then
        Left(
          s"Relationship $relType endpoint '${nodeMap.id}' has label ${nodeMap.label}, expected $expectedLabel"
        )
      else
        Right(keys.map: keyField =>
          // Edge key graph field -> physical source; match to node id by position/name
          val src = ds.sourceFor(keyField.name).getOrElse(keyField.name)
          // Prefer node id field with same physical source, else first id
          val nodeType = schema.requireNode(nodeMap.label)
          val nodeDs = nodeType.table.get
          val graphField =
            nodeType.id
              .find(f => nodeDs.sourceFor(f.name).getOrElse(f.name) == src)
              .map(_.name)
              .orElse(nodeType.id.headOption.map(_.name))
              .getOrElse(keyField.name)
          val csvCol = nodeMap.columns.getOrElse(
            graphField,
            return Left(s"Node '${nodeMap.id}' missing column for '$graphField'")
          )
          src -> csvRow.getOrElse(csvCol, "")
        )

    for
      fromPairs <- endpointValues(fromNode, et.fromKey, et.fromNodeLabel)
      toPairs   <- endpointValues(toNode, et.toKey, et.toNodeLabel)
    yield
      if fromPairs.exists(_._2.isEmpty) || toPairs.exists(_._2.isEmpty) then ()
      else
        val physical = mutable.LinkedHashMap.empty[String, String]
        fromPairs.foreach { case (src, v) => physical(src) = v }
        toPairs.foreach { case (src, v) => physical(src) = v }
        // Also fill id fields that share sources
        et.id.foreach: f =>
          val src = ds.sourceFor(f.name).getOrElse(f.name)
          if !physical.contains(src) then
            physical.get(src).foreach(_ => ())
          physical.get(src) match
            case Some(_) => ()
            case None =>
              // copy from from/to if same source already set — already in map
              ()
        // Ensure id physical cols populated from from/to when mapped to same sources
        et.id.foreach: f =>
          val src = ds.sourceFor(f.name).getOrElse(f.name)
          if !physical.contains(src) then
            fromPairs.find(_._1 == src).orElse(toPairs.find(_._1 == src)).foreach {
              case (_, v) => physical(src) = v
            }
        val buf = edgeRows.getOrElseUpdate(relType, mutable.ArrayBuffer.empty)
        buf += physical.toMap

  private def buildTables(
      schema: GraphSchema,
      nodeRows: mutable.LinkedHashMap[String, mutable.LinkedHashMap[String, Map[String, String]]],
      edgeRows: mutable.LinkedHashMap[String, mutable.ArrayBuffer[Map[String, String]]]
  ): Either[String, Map[String, Table]] =
    val tables = mutable.LinkedHashMap.empty[String, Table]

    nodeRows.foreach { case (label, rows) =>
      val nt = schema.requireNode(label)
      val ds = nt.table.getOrElse(return Left(s"Node '$label' has no table"))
      val cols = collectColumns(rows.values.toList)
      tables(ds.qualifiedTable) = mergeTable(tables.get(ds.qualifiedTable), cols, rows.values.toList)
    }

    edgeRows.foreach { case (relType, rows) =>
      val et = schema.requireEdge(relType)
      val ds = et.table.getOrElse(return Left(s"Edge '$relType' has no table"))
      val cols = collectColumns(rows.toList)
      // Co-located: same qualified table as a node — merge carefully
      tables.get(ds.qualifiedTable) match
        case Some(existing) if nodeRows.contains(et.toNodeLabel) || nodeRows.contains(et.fromNodeLabel) =>
          // Edge shares table with node (e.g. citizenship). Append edge-needed cols onto a dedicated
          // binding only if same table — for HAS_INN, person_inn is edge-only.
          tables(ds.qualifiedTable) = mergeTable(Some(existing), cols, rows.toList)
        case _ =>
          tables(ds.qualifiedTable) = mergeTable(tables.get(ds.qualifiedTable), cols, rows.toList)
    }

    Right(tables.toMap)

  private def collectColumns(rows: List[Map[String, String]]): List[String] =
    rows.flatMap(_.keys).distinct.sorted

  private def mergeTable(
      existing: Option[Table],
      cols: List[String],
      rows: List[Map[String, String]]
  ): Table =
    val allCols =
      (existing.map(t => t.columnNames().asScala.toList).getOrElse(Nil) ++ cols).distinct
    val data = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[String]]
    allCols.foreach(c => data(c) = mutable.ArrayBuffer.empty)

    existing.foreach: t =>
      val names = t.columnNames().asScala.toList
      (0 until t.rowCount()).foreach: i =>
        allCols.foreach: c =>
          val v =
            if names.contains(c) then Option(t.getString(i, c)).getOrElse("")
            else ""
          data(c) += v

    rows.foreach: row =>
      allCols.foreach: c =>
        data(c) += row.getOrElse(c, "")

    val table = Table.create("materialized")
    allCols.foreach: c =>
      table.addColumns(StringColumn.create(c, data(c).toSeq*))
    table

  private def snapshot(table: Table): List[Map[String, String]] =
    val cols = table.columnNames().asScala.toList
    (0 until table.rowCount()).map: i =>
      cols.map(c => c -> Option(table.getString(i, c)).getOrElse("")).toMap
    .toList
