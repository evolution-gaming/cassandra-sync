import sbt._

object Dependencies {

  val scalatest = "org.scalatest" %% "scalatest" % "3.2.20"
  val `testcontainers-cassandra` = "com.dimafeng" %% "testcontainers-scala-cassandra" % "0.44.1"
  val `future-helper` = "com.evolutiongaming" %% "future-helper" % "1.0.7"
  val scassandra = "com.evolutiongaming" %% "scassandra" % "5.6.0"

  object Logback {
    private val version = "1.6.4"
    val core = "ch.qos.logback" % "logback-core" % version
    val classic = "ch.qos.logback" % "logback-classic" % version
  }

  object Slf4j {
    private val version = "2.0.20"
    val api = "org.slf4j" % "slf4j-api" % version
    val `log4j-over-slf4j` = "org.slf4j" % "log4j-over-slf4j" % version
  }

  object Netty {
    private val version = "4.1.138.Final"
    val all = Seq(
      "netty-buffer",
      "netty-codec",
      "netty-common",
      "netty-handler",
      "netty-resolver",
      "netty-transport",
      "netty-transport-native-unix-common",
    ).map("io.netty" % _ % version)
  }

  object Jackson {
    private val version = "2.22.3"
    val core = "com.fasterxml.jackson.core" % "jackson-core" % version
    val databind = "com.fasterxml.jackson.core" % "jackson-databind" % version
    val annotations = "com.fasterxml.jackson.core" % "jackson-annotations" % "2.22"
  }

  val guava = "com.google.guava" % "guava" % "33.7.1-jre"

  val overrides: Seq[ModuleID] = Netty.all ++ Seq(Jackson.core, Jackson.databind, Jackson.annotations, guava)
}
