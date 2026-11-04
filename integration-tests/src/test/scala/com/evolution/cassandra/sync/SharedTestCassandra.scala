package com.evolution.cassandra.sync

import cats.effect.*
import com.datastax.oss.driver.api.core.CqlSessionBuilder
import com.evolution.scassandra.*
import org.slf4j.LoggerFactory
import org.testcontainers.cassandra.CassandraContainer

import java.time.Instant
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Cassandra test resources shared between test suites.
 *
 * Consists of a running Cassandra server test container and [[CassandraSession]] connected to it.
 *
 * Intended usage:
 *   - create an instance once for a set of test suites
 *   - call [[start()]] in beforeAll of [[org.scalatest.Suites]] combining them
 *   - call [[stop()]] in afterAll of [[org.scalatest.Suites]]
 *   - call [[waitForStartedSession]] in child test suites, outside their constructors, to access
 *     the session
 *
 * If the start process fails, it won't be retried and the same error will be returned on subsequent
 * operations.
 *
 * @param cassandraDockerImage
 *   test cassandra server docker image
 * @param cassandraVersion
 *   cassandra server version string - can be used for logging and to parameterize test suite names
 *
 * @see
 *   [[SharedTestCassandra.makeV4]] [[SharedTestCassandra.makeV5]]
 */
final class SharedTestCassandra private (
  cassandraDockerImage: String,
  val cassandraVersion: String,
) {
  private val logger = LoggerFactory.getLogger(getClass)

  private lazy val startedLazyTry: Try[(CassandraSession[IO], IO[Unit])] = {
    logger.info(s"[$cassandraDockerImage] starting...")
    val startTime = Instant.now()
    Try {
      val result = resources.allocated.unsafeRunSync()(cats.effect.unsafe.implicits.global)
      logger.info(s"[$cassandraDockerImage] started in ${ java.time.Duration.between(startTime, Instant.now()) }")
      result
    }
  }

  def start(): Unit = {
    startedLazyTry.get
    ()
  }

  def stop(): Unit = {
    startedLazyTry.foreach {
      case (_, releaseIo) =>
        logger.info(s"[$cassandraDockerImage] stopping...")
        val startTime = Instant.now()
        try {
          releaseIo.unsafeRunSync()(cats.effect.unsafe.implicits.global)
          logger.info(s"[$cassandraDockerImage] stopped in ${ java.time.Duration.between(startTime, Instant.now()) }")
        } catch {
          case NonFatal(t) =>
            logger.warn(s"[$cassandraDockerImage] stop failed", t)
        }
    }
  }

  def waitForStartedSession: CassandraSession[IO] = {
    val (session, _) = startedLazyTry.get; session
  }

  private def resources: ResourceIO[CassandraSession[IO]] =
    serverResource.flatMap(sessionResource)

  private def serverResource: ResourceIO[CassandraContainer] = {
    val idleResource = Resource.make(IO {
      new CassandraContainer(cassandraDockerImage)
    }) { container =>
      IO.blocking {
        container.stop()
      }
    }

    idleResource.evalMap(container => IO.blocking { container.start(); container })
  }

  private def sessionResource(server: CassandraContainer): ResourceIO[CassandraSession[IO]] = {
    CassandraSession.make[IO](
      new CqlSessionBuilder()
        .addContactPoint(server.getContactPoint)
        .withLocalDatacenter(server.getLocalDatacenter),
    )
  }
}

object SharedTestCassandra {
  private def dockerImage(version: String): String = s"cassandra:$version"

  /**
   * Creates a new instance of [[SharedTestCassandra]] for Cassandra server version 4.x
   */
  def makeV4: SharedTestCassandra = {
    val version = "4.1.12"
    new SharedTestCassandra(
      cassandraDockerImage = dockerImage(version),
      cassandraVersion = version,
    )
  }

  /**
   * Creates a new instance of [[SharedTestCassandra]] for Cassandra server version 5.x
   */
  def makeV5: SharedTestCassandra = {
    val version = "5.0.9"
    new SharedTestCassandra(
      cassandraDockerImage = dockerImage(version),
      cassandraVersion = version,
    )
  }
}
