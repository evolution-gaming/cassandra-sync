package com.evolution.cassandra.sync

import cats.effect.IO
import com.evolution.scassandra.{CassandraSession, CreateKeyspaceIfNotExists, ReplicationStrategyConfig}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

/**
 * Base Cassandra-based integration test suite.
 *
 * Parameterized with [[SharedTestCassandra]] to be able to perform tests against different
 * Cassandra versions.
 *
 * @param testCassandra
 *   concrete [[SharedTestCassandra]] instance
 */
abstract class BaseCassandraSpec(testCassandra: SharedTestCassandra)
extends AnyWordSpec
with Matchers
with OptionValues
with BeforeAndAfterAll
with IntegrationIOSuite {

  protected final lazy val session: CassandraSession[IO] = testCassandra.waitForStartedSession

  // Overriding suite coordinates to prefix them with the Cassandra version.
  // Without this test suites for different Cassandra version look the same in logs.
  override final def suiteName: String = s"${ super.suiteName }-cassandra-${ testCassandra.cassandraVersion }"
  override final def suiteId: String = s"${ super.suiteId }@cassandra-${ testCassandra.cassandraVersion }"

  /**
   * Creates a test keyspace for the test suite.
   *
   * Provided name is prefixed with a test-suite-specific prefix to isolate the suites from each
   * other.
   *
   * @param localName
   *   in-spec local name for the keyspace
   * @return
   *   generated keyspace name
   */
  protected final def ensureTestKeyspace(localName: String): String = {
    val specNamePrefix = suiteName.replaceAll("([^a-zA-Z0-9])+", "_").toLowerCase
    val name = s"${ specNamePrefix }_$localName"

    session.execute(CreateKeyspaceIfNotExists(name, ReplicationStrategyConfig.Default)).void.run()

    name
  }
}
