import Dependencies.*

lazy val commonSettings = Seq(
  organization := "com.evolutiongaming",
  homepage := Some(uri("https://github.com/evolution-gaming/cassandra-sync")),
  startYear := Some(2018),
  organizationName := "Evolution",
  organizationHomepage := Some(uri("https://evolution.com")),
  versionPolicyIntention := Compatibility.BinaryCompatible,
  scalaVersion := crossScalaVersions.value.head,
  // if changing binary Scala versions here, change in the ci.yml action as well
  crossScalaVersions := Seq("2.13.18", "3.3.8"),

  scalacOptions ++= Seq(
    "-release:17",
    "-deprecation",
  ),
  scalacOptions ++= crossSettings(
    scalaVersion = scalaVersion.value,
    // Good compiler options for Scala 2.13 are coming from com.evolution:sbt-scalac-opts-plugin:0.1.0,
    // but its support for Scala 3 is limited, especially what concerns linting options.
    //
    // If Scala 3 is made the primary target, good linting scalac options for it should be added first.
    if3 = Seq(
      "-Ykind-projector:underscores",

      // disable new brace-less syntax:
      // https://alexn.org/blog/2022/10/24/scala-3-optional-braces/
      "-no-indent",

      // improve error messages:
      "-explain",
      "-explain-types",
    ),
    if2 = Seq(
      "-Xsource:3",
    ),
  ),
  Compile / doc / scalacOptions ++= Seq("-groups", "-implicits", "-no-link-warnings"),
  Compile / doc / scalacOptions -= "-Xfatal-warnings",

  publishTo := Some(Resolver.evolutionReleases),
  licenses := Seq(("MIT", uri("https://opensource.org/licenses/MIT"))),
  dependencyOverrides ++= Dependencies.overrides,
  versionPolicyIgnored ++= Dependencies.overrides.map(m => m.organization % m.name),
)

addCommandAlias("fmt", "+all scalafmtRepo")
// check is used as +check by the release GH action
lazy val checkCmd = "all scalafmtCheckRepo versionPolicyCheck Compile/doc"
addCommandAlias("check", checkCmd)
addCommandAlias("build", s"+$checkCmd testFull")

lazy val root = project
  .in(file("."))
  .settings(name := "cassandra-sync-root")
  .settings(commonSettings)
  .settings(
    publish / skip := true,
  )
  .aggregate(`cassandra-sync`, tests)

lazy val `cassandra-sync` = project
  .in(file("cassandra-sync"))
  .settings(name := "cassandra-sync")
  .settings(commonSettings)
  .settings(libraryDependencies ++= Seq(
    `future-helper`,
    scalatest % Test,
    scassandra,
  ))

lazy val tests = project
  .in(file("tests"))
  .settings(name := "tests")
  .settings(commonSettings)
  .settings(Seq(
    publish / skip := true,
    Test / fork := true,
    Test / parallelExecution := false,
  ))
  .dependsOn(`cassandra-sync` % "test->test;compile->compile")
  .settings(libraryDependencies ++= Seq(
    `testcontainers-cassandra` % Test,
    Slf4j.api % Test,
    Slf4j.`log4j-over-slf4j` % Test,
    Logback.core % Test,
    Logback.classic % Test,
    scalatest % Test,
  ))

def crossSettings[T](scalaVersion: String, if3: T, if2: T): T = {
  scalaVersion match {
    case version if version.startsWith("3") => if3
    case _ => if2
  }
}
