package com.evolution.cassandra.sync

/**
 * Combines all the Cassandra-based integration test suites to share [[SharedTestCassandra]] between
 * them - runs against a Cassandra 4.x server.
 */
class CassandraV4Specs extends BaseCassandraSpecs(SharedTestCassandra.makeV4)
