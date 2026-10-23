import sbt.*

object Dependencies {

  val scalatest = "org.scalatest" %% "scalatest" % "3.2.20"
  val `testcontainers-cassandra` = ("org.testcontainers" % "testcontainers-cassandra" % "2.0.5")
    // the driver is used only by the deprecated org.testcontainers.containers.CassandraContainer,
    // excluding it to keep driver 3 classes off the classpath of the tests for other drivers
    .exclude("com.datastax.cassandra", "cassandra-driver-core")
  val scassandra = "com.evolution" %% "scassandra-v4" % "5.8.0"
  // should match the cats-effect version coming transitively from scassandra
  val `cats-effect-testkit` = "org.typelevel" %% "cats-effect-testkit" % "3.7.1"

  object Logback {
    private val version = "1.6.5"
    val core = "ch.qos.logback" % "logback-core" % version
    val classic = "ch.qos.logback" % "logback-classic" % version
  }

  object Slf4j {
    private val version = "2.0.20"
    val api = "org.slf4j" % "slf4j-api" % version
  }

  // https://github.com/evolution-gaming/cassandra-sync/security/dependabot
  // Version overrides to fix dependabot alerts go here:
  object SecurityAlertOverrides {
    private object Jackson {
      private val version = "2.22.4"

      val databind = "com.fasterxml.jackson.core" % "jackson-databind" % version
      val core = "com.fasterxml.jackson.core" % "jackson-core" % version
    }

    private val nettyHandler = "io.netty" % "netty-handler" % "4.1.139.Final"

    val dependencies: Seq[ModuleID] = Seq(
      Jackson.databind,
      Jackson.core,
      nettyHandler,
    )
  }
}
