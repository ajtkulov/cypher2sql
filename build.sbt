ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.6"

lazy val root = (project in file("."))
  .settings(
    name := "cypher2sql",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
      "io.circe" %% "circe-core"    % "0.14.12",
      "io.circe" %% "circe-generic" % "0.14.12",
      "io.circe" %% "circe-parser"  % "0.14.12",
      "org.rogach" %% "scallop" % "5.3.0",
      // DataFrame-like CSV tables for in-memory subgraph execution (no Spark).
      "tech.tablesaw" % "tablesaw-core" % "0.43.1",
      // Optional Apache Arrow interchange — enable when implementing ArrowInterop:
      // "org.apache.arrow" % "arrow-vector" % "15.0.2",
      // "org.apache.arrow" % "arrow-memory-netty" % "15.0.2",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    Compile / mainClass := Some("cypher2sql.main"),
    assembly / mainClass := Some("cypher2sql.main"),
    assembly / assemblyJarName := s"${name.value}-assembly-${version.value}.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case "module-info.class"      => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
