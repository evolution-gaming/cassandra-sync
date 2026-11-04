package com.evolution.cassandra.sync

/**
 * Combines all the Cassandra-based integration test suites to share [[SharedTestCassandra]] between
 * them - runs against a Cassandra 5.x server.
 */
class CassandraV5Specs extends BaseCassandraSpecs(SharedTestCassandra.makeV5)
