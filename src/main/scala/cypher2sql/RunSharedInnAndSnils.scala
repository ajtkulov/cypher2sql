package cypher2sql

import cypher2sql.graph.GraphMaterializer
import cypher2sql.mapping.CsvGraphMapping
import cypher2sql.schema.SchemaReader
import tech.tablesaw.api.Table

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable

/**
 * Execute the score>=2 shared-identifier query over data/ CSVs.
 *
 * Semantics of data/query_shared_inn_and_snils.cypher:
 *   each of INN / SNILS / PASSPORT shared between p1 and p2 contributes +1;
 *   keep rows with score >= 2; return shared identifier values.
 *
 * Implemented via bridge-pair intersection (SubgraphExecutor does not yet
 * support OPTIONAL MATCH / WITH).
 */
@main def runSharedInnAndSnils(): Unit =
  val root = Path.of(".")
  val outPath = root.resolve("data/query_shared_inn_and_snils.out")

  val schema = SchemaReader.readPath(root.resolve("schema.json")) match
    case Right(s) => s
    case Left(e) =>
      System.err.println(e); sys.exit(1)

  val mapping =
    CsvGraphMapping
      .load(root.resolve("data/mapping.all.json"))
      .flatMap(CsvGraphMapping.validateAgainstSchema(_, schema)) match
      case Right(m) => m
      case Left(e) =>
        System.err.println(e); sys.exit(1)

  println("Loading all data/*.csv into graph...")
  val t0 = System.nanoTime()
  val graph = GraphMaterializer.materialize(schema, mapping, root.resolve("data")) match
    case Right(g) => g
    case Left(e) =>
      System.err.println(e); sys.exit(1)
  println(f"Graph ready in ${(System.nanoTime() - t0) / 1e9}%.1fs")

  val cypher = Files.readString(root.resolve("data/query_shared_inn_and_snils.cypher"))
  println("Query:")
  println(cypher.trim)
  println()

  val persons = graph.nodeTable("Person").fold(e => { System.err.println(e); sys.exit(1) }, identity)
  val hasInn = graph.edgeTable("HAS_INN").fold(e => { System.err.println(e); sys.exit(1) }, _._2)
  val hasSnils = graph.edgeTable("HAS_SNILS").fold(e => { System.err.println(e); sys.exit(1) }, _._2)
  val hasPassport =
    graph.edgeTable("HAS_PASSPORT").fold(e => { System.err.println(e); sys.exit(1) }, _._2)

  /** pair -> shared bridge values for that identifier type */
  def sharedByPair(edge: Table, personCol: String, bridgeCol: String): Map[(String, String), Set[String]] =
    val byBridge = mutable.Map.empty[String, Set[String]]
    (0 until edge.rowCount()).foreach: i =>
      val person = Option(edge.getString(i, personCol)).getOrElse("")
      val bridge = Option(edge.getString(i, bridgeCol)).getOrElse("")
      if person.nonEmpty && bridge.nonEmpty then
        byBridge(bridge) = byBridge.getOrElse(bridge, Set.empty) + person
    val out = mutable.Map.empty[(String, String), mutable.Set[String]]
    byBridge.foreach: (bridge, people) =>
      if people.size >= 2 then
        val sorted = people.toList.sorted
        for
          a <- sorted
          b <- sorted
          if a < b
        do out.getOrElseUpdate((a, b), mutable.Set.empty) += bridge
    out.view.mapValues(_.toSet).toMap

  val t1 = System.nanoTime()
  val innByPair = sharedByPair(hasInn, "person_hash", "innCode")
  val snilsByPair = sharedByPair(hasSnils, "person_hash", "snils")
  val passportByPair = sharedByPair(hasPassport, "person_hash", "passport")

  val candidates = innByPair.keySet ++ snilsByPair.keySet ++ passportByPair.keySet

  def opts(values: Set[String]): List[Option[String]] =
    if values.isEmpty then List(None) else values.toList.sorted.map(Some(_))

  val personProps: Map[String, Map[String, String]] =
    (0 until persons.rowCount()).map { i =>
      val h = Option(persons.getString(i, "person_hash")).getOrElse("")
      h -> Map(
        "person_hash" -> h,
        "first_name" -> Option(persons.getString(i, "first_name")).getOrElse(""),
        "last_name" -> Option(persons.getString(i, "last_name")).getOrElse(""),
        "middle_name" -> Option(persons.getString(i, "middle_name")).getOrElse(""),
        "birth_date" -> Option(persons.getString(i, "birth_date")).getOrElse("")
      )
    }.toMap

  def fmtPerson(hash: String): String =
    personProps.get(hash) match
      case Some(p) =>
        s"${p("last_name")} ${p("first_name")} ${p("middle_name")}".trim +
          s" | ${p("birth_date")} | ${p("person_hash")}"
      case None => hash

  // Rows match RETURN p1, p2, score, i.innCode, s.snils, pass.passport
  // (cartesian of optional bindings, like Cypher OPTIONAL MATCH)
  val rows =
    candidates.toList
      .flatMap: pair =>
        val inns = innByPair.getOrElse(pair, Set.empty)
        val snilses = snilsByPair.getOrElse(pair, Set.empty)
        val passports = passportByPair.getOrElse(pair, Set.empty)
        val score =
          (if inns.nonEmpty then 1 else 0) +
            (if snilses.nonEmpty then 1 else 0) +
            (if passports.nonEmpty then 1 else 0)
        if score < 2 then Nil
        else
          for
            i <- opts(inns)
            s <- opts(snilses)
            p <- opts(passports)
          yield (pair, score, i, s, p)
      .sortBy { case ((a, b), score, i, s, p) =>
        (-score, a, b, i.getOrElse(""), s.getOrElse(""), p.getOrElse(""))
      }

  val pairCount = rows.map(_._1).distinct.size

  val sb = new StringBuilder
  sb.append("# query_shared_inn_and_snils — score >= 2\n")
  sb.append(s"# pairs: $pairCount  rows: ${rows.size}\n")
  sb.append(
    s"# inn pairs: ${innByPair.size}  snils pairs: ${snilsByPair.size}  passport pairs: ${passportByPair.size}\n"
  )
  sb.append("score\tp1\tp2\ti.innCode\ts.snils\tpass.passport\n")
  rows.foreach { case ((a, b), score, i, s, p) =>
    sb.append(
      s"$score\t${fmtPerson(a)}\t${fmtPerson(b)}\t${i.getOrElse("")}\t${s.getOrElse("")}\t${p.getOrElse("")}\n"
    )
  }

  Files.writeString(outPath, sb.toString, StandardCharsets.UTF_8)
  println(f"Done in ${(System.nanoTime() - t1) / 1e9}%.1fs")
  println(s"pairs with score >= 2: $pairCount  rows: ${rows.size}")
  println(s"wrote $outPath")
