package cypher2sql

import cypher2sql.graph.GraphMaterializer
import cypher2sql.mapping.CsvGraphMapping
import cypher2sql.schema.SchemaReader
import tech.tablesaw.api.Table

import java.nio.file.Path

/** Load all /data CSVs and report shared INN / SNILS person paths. */
@main def findSharedPaths(): Unit =
  val root = Path.of(".")
  val schema = SchemaReader.readPath(root.resolve("schema.json")) match
    case Right(s) => s
    case Left(e) =>
      System.err.println(e)
      sys.exit(1)

  val mapping =
    CsvGraphMapping
      .load(root.resolve("data/mapping.all.json"))
      .flatMap(CsvGraphMapping.validateAgainstSchema(_, schema)) match
      case Right(m) => m
      case Left(e) =>
        System.err.println(e)
        sys.exit(1)

  println("Materializing graph from data/ (all *.csv via mapping.all.json)...")
  val t0 = System.nanoTime()
  val graph = GraphMaterializer.materialize(schema, mapping, root.resolve("data")) match
    case Right(g) => g
    case Left(e) =>
      System.err.println(e)
      sys.exit(1)
  println(f"Materialized in ${(System.nanoTime() - t0) / 1e9}%.2fs")

  val persons = graph.nodeTable("Person").fold(e => { System.err.println(e); sys.exit(1) }, identity)
  val inns = graph.nodeTable("INN").fold(e => { System.err.println(e); sys.exit(1) }, identity)
  val snils = graph.nodeTable("SNILS").fold(e => { System.err.println(e); sys.exit(1) }, identity)
  val hasInn = graph.edgeTable("HAS_INN").fold(e => { System.err.println(e); sys.exit(1) }, _._2)
  val hasSnils = graph.edgeTable("HAS_SNILS").fold(e => { System.err.println(e); sys.exit(1) }, _._2)

  println()
  println("Graph size:")
  println(s"  Person: ${persons.rowCount()}")
  println(s"  INN: ${inns.rowCount()}")
  println(s"  SNILS: ${snils.rowCount()}")
  println(s"  HAS_INN: ${hasInn.rowCount()}")
  println(s"  HAS_SNILS: ${hasSnils.rowCount()}")
  println()

  /** Unordered person pairs that share the same bridge value (INN/SNILS). */
  def sharedPairs(edge: Table, personCol: String, bridgeCol: String): (Set[(String, String)], Int) =
    val byBridge = scala.collection.mutable.Map.empty[String, Set[String]]
    (0 until edge.rowCount()).foreach: i =>
      val person = Option(edge.getString(i, personCol)).getOrElse("")
      val bridge = Option(edge.getString(i, bridgeCol)).getOrElse("")
      if person.nonEmpty && bridge.nonEmpty then
        byBridge(bridge) = byBridge.getOrElse(bridge, Set.empty) + person
    val multi = byBridge.filter(_._2.size >= 2)
    val pairs = multi.values.flatMap { people =>
      val sorted = people.toList.sorted
      for
        a <- sorted
        b <- sorted
        if a < b
      yield (a, b)
    }.toSet
    (pairs, multi.size)

  val (innPairs, innBridges) = sharedPairs(hasInn, "person_hash", "innCode")
  val (snilsPairs, snilsBridges) = sharedPairs(hasSnils, "person_hash", "snils")
  val both = innPairs.intersect(snilsPairs)

  val personNames: Map[String, String] =
    (0 until persons.rowCount()).map { i =>
      val h = Option(persons.getString(i, "person_hash")).getOrElse("")
      val ln = Option(persons.getString(i, "last_name")).getOrElse("")
      val fn = Option(persons.getString(i, "first_name")).getOrElse("")
      h -> s"$ln $fn"
    }.toMap

  def nameOf(hash: String): String =
    personNames.get(hash).map(n => s"$n ($hash)").getOrElse(hash)

  println("Path: (p1:Person)-[:HAS_INN]->(i:INN)<-[:HAS_INN]-(p2:Person)")
  println(s"  Bridge INNs (shared by ≥2 persons): $innBridges")
  println(s"  Distinct person pairs: ${innPairs.size}")
  println()
  println("Path: (p1:Person)-[:HAS_SNILS]->(s:SNILS)<-[:HAS_SNILS]-(p2:Person)")
  println(s"  Bridge SNILS (shared by ≥2 persons): $snilsBridges")
  println(s"  Distinct person pairs: ${snilsPairs.size}")
  println()
  println("Common pairs (same two persons share BOTH an INN and a SNILS):")
  println(s"  Count: ${both.size}")
  both.toList.sorted.take(25).foreach { case (a, b) =>
    println(s"  ${nameOf(a)}")
    println(s"    <->")
    println(s"  ${nameOf(b)}")
    println()
  }
  if both.size > 25 then println(s"  ... (${both.size - 25} more)")
