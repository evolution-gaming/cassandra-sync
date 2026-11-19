package com.evolution.cassandra.sync

/**
 * Locks table schema and queries of cassandra-sync 4.x, which has no acquisition ID column.
 *
 * Used to verify that the locks tables created by 4.x versions are rejected, and that the lock rows
 * written by 4.x versions are not taken over.
 */
object LockStatements4x {

  def createTableIfNotExists(keyspace: String, table: String): String =
    s"CREATE TABLE IF NOT EXISTS $keyspace.$table (" +
      "id text PRIMARY KEY, " +
      "expiry_ms BIGINT, " +
      "timestamp TIMESTAMP," +
      "metadata TEXT)"

  def insertIfNotExists(keyspace: String, table: String): String =
    s"INSERT INTO $keyspace.$table (id, expiry_ms, timestamp, metadata) VALUES (?, ?, ?, ?) IF NOT EXISTS USING TTL ?"

  def delete(keyspace: String, table: String): String =
    s"DELETE FROM $keyspace.$table WHERE id = ? IF EXISTS"
}
