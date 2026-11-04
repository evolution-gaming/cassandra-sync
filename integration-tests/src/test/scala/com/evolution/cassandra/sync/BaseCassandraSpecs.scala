package com.evolution.cassandra.sync

import com.evolution.cassandra.sync.impl.CassandraLockStoreSpec
import org.scalatest.{BeforeAndAfterAll, Suites}

/**
 * Combines all the Cassandra-based integration test suites to share [[SharedTestCassandra]] between
 * them.
 *
 * Parameterized with [[SharedTestCassandra]] to be able to perform tests against different
 * Cassandra versions.
 *
 * @param testCassandra
 *   concrete [[SharedTestCassandra]] instance
 */
abstract class BaseCassandraSpecs(testCassandra: SharedTestCassandra) extends Suites(
  new CassandraLockStoreSpec(testCassandra),
  new CassandraSyncSpec(testCassandra),
) with BeforeAndAfterAll {
  override def beforeAll(): Unit = {
    super.beforeAll()
    testCassandra.start()
  }

  override def afterAll(): Unit = {
    testCassandra.stop()
    super.afterAll()
  }
}
