package cypher2sql

import cypher2sql.parser.CypherParser

@main def main(args: String*): Unit =
  val cypher =
    if args.nonEmpty then args.mkString(" ")
    else
      """
        MATCH (a:Person)-[r:KNOWS]->(b:Person)
        WHERE a.age > 30
        RETURN a.name, b.name AS friend
        ORDER BY a.age DESC
        LIMIT 10
      """

  CypherParser.parse(cypher) match
    case Right(query) =>
      println("Parsed IR/AST:")
      pprint(query)
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)

private def pprint(value: Any, indent: Int = 0): Unit =
  val pad = "  " * indent
  value match
    case s: String =>
      println(s"$pad$s")
    case p: Product if p.productArity > 0 =>
      println(s"$pad${p.productPrefix}(")
      p.productIterator.foreach(pprint(_, indent + 1))
      println(s"$pad)")
    case xs: Iterable[?] =>
      println(s"$pad[")
      xs.foreach(pprint(_, indent + 1))
      println(s"$pad]")
    case other =>
      println(s"$pad$other")
