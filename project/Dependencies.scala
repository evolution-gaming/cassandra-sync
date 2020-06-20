import sbt._

object Dependencies {

  val scalatest            = "org.scalatest"       %% "scalatest"          % "3.2.0"
  val `future-helper`      = "com.evolutiongaming" %% "future-helper"      % "1.0.6"
  val `cassandra-launcher` = "com.evolutiongaming" %% "cassandra-launcher" % "0.0.3"
  val scassandra           = "com.evolutiongaming" %% "scassandra"         % "3.0.1"

  object Logback {
    private val version = "1.2.3"
    val core    = "ch.qos.logback" % "logback-core"    % version
    val classic = "ch.qos.logback" % "logback-classic" % version
  }

  object Slf4j {
    private val version = "1.7.30"
    val api                = "org.slf4j" % "slf4j-api"        % version
    val `log4j-over-slf4j` = "org.slf4j" % "log4j-over-slf4j" % version
  }
}