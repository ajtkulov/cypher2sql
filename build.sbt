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
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    )
  )
