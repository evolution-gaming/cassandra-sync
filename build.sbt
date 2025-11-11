import Dependencies.*

lazy val commonSettings = Seq(
  organization := "com.evolutiongaming",
  homepage := Some(url("https://github.com/evolution-gaming/cassandra-sync")),
  startYear := Some(2018),
  organizationName := "Evolution",
  organizationHomepage := Some(url("https://evolution.com")),
  versionPolicyIntention := Compatibility.BinaryCompatible,
  scalaVersion := crossScalaVersions.value.head,
  crossScalaVersions := Seq("2.13.16", "3.3.7"),
  Compile / doc / scalacOptions ++= Seq("-groups", "-implicits", "-no-link-warnings"),
  publishTo := Some(Resolver.evolutionReleases),
  licenses := Seq(("MIT", url("https://opensource.org/licenses/MIT"))))

val alias: Seq[sbt.Def.Setting[?]] =
  addCommandAlias("check", "all versionPolicyCheck Compile/doc") ++
    addCommandAlias("build", "all compile test")

lazy val root = (project in file(".")
  settings (name := "cassandra-sync")
  settings commonSettings
  settings (alias)
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
  `testcontainers-cassandra` % Test,
  Slf4j.api % Test,
  Slf4j.`log4j-over-slf4j` % Test,
  Logback.core % Test,
  Logback.classic % Test,
  scalatest % Test)))
