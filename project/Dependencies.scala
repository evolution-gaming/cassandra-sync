import sbt._

object Dependencies {

  val scalatest            = "org.scalatest"       %% "scalatest"          % "3.2.20"
  val `testcontainers-cassandra` = "com.dimafeng"           %% "testcontainers-scala-cassandra"  % "0.44.1"
  val `future-helper`      = "com.evolutiongaming" %% "future-helper"      % "1.0.7"
  val scassandra           = "com.evolutiongaming" %% "scassandra"         % "5.6.0"

  object Logback {
    private val version = "1.5.38"
    val core    = "ch.qos.logback" % "logback-core"    % version
    val classic = "ch.qos.logback" % "logback-classic" % version
  }

  object Slf4j {
    private val version = "2.0.18"
    val api                = "org.slf4j" % "slf4j-api"        % version
    val `log4j-over-slf4j` = "org.slf4j" % "log4j-over-slf4j" % version
  }
}
