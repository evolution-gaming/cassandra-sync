import Dependencies._

lazy val commonSettings = Seq(
  organization := "com.evolutiongaming",
  homepage := Some(new URL("http://github.com/evolution-gaming/cassandra-sync")),
  startYear := Some(2018),
  organizationName := "Evolution Gaming",
  organizationHomepage := Some(url("http://evolutiongaming.com")),
  scalaVersion := crossScalaVersions.value.head,
  crossScalaVersions := Seq("2.13.14", "2.12.13"),
  Compile / doc / scalacOptions ++= Seq("-groups", "-implicits", "-no-link-warnings"),
  publishTo := Some(Resolver.evolutionReleases),
  licenses := Seq(("MIT", url("https://opensource.org/licenses/MIT"))),
  releaseCrossBuild := true)

lazy val root = (project in file(".")
  settings (name := "cassandra-sync")
  settings commonSettings
  settings (
  publish / skip := true,
  skip / publishArtifact := true)
  aggregate(`cassandra-sync`, tests))

lazy val `cassandra-sync` = (project in file("cassandra-sync")
  settings (name := "cassandra-sync")
  settings commonSettings
  settings (libraryDependencies ++= Seq(
  `future-helper`,
  scalatest % Test,
  scassandra)))

lazy val tests = (project in file("tests")
  settings (name := "tests")
  settings commonSettings
  settings Seq(
  publish / skip := true,
  skip / publishArtifact := true,
  Test / fork := true,
  Test / parallelExecution := false)
  dependsOn `cassandra-sync` % "test->test;compile->compile"
  settings (libraryDependencies ++= Seq(
  `cassandra-launcher` % Test,
  Slf4j.api % Test,
  Slf4j.`log4j-over-slf4j` % Test,
  Logback.core % Test,
  Logback.classic % Test,
  scalatest % Test)))
