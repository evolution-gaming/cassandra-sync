package com.evolution.cassandra.sync.impl

import cats.effect.*
import cats.implicits.*
import com.datastax.oss.driver.api.core.cql.*
import com.evolution.scassandra.*
import com.evolution.scassandra.syntax.*

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
 * Lock storage abstraction over Cassandra for [[CassandraSyncImpl]].
 *
 * All operations here are meant to be executed with LWTs thus having linearizable guarantees per
 * lock row.
 */
private[sync] trait LockStore[F[_]] {

  /**
   * Inserts a lock row identified by ID if it wasn't present before.
   *
   * Returns true if a new row was inserted, false if a row with such ID already existed and nothing
   * was inserted.
   *
   * @param id
   *   lock ID
   * @param expiry
   *   lock row expiration TTL, rounded up to seconds, if < 1s, 1s TTL is used
   * @param timestamp
   *   acquisition timestamp
   * @param metadata
   *   you can provide additional information for tracing origin of lock
   */
  def insertIfNotExists(
    id: String,
    expiry: FiniteDuration,
    timestamp: Instant,
    metadata: Option[String],
  ): F[Boolean]

  /**
   * Deletes a lock row identified by ID.
   *
   * If a lock row didn't exist, the operation should silently succeed.
   *
   * @param id
   *   lock ID
   */
  def delete(id: String): F[Unit]
}

private[sync] object LockStore {

  /**
   * Creates a default [[LockStore]] implementation backed by a Cassandra table.
   *
   * @param session
   *   cassandra session for executing statements
   * @param statements
   *   see [[LockStatements]]
   */
  def of[F[_]: Sync](
    session: CassandraSession[F],
    statements: LockStatements,
  ): F[LockStore[F]] = {

    for {
      insertIfNotExistsSt <- session.prepare(statements.InsertIfNotExists)
      deleteSt <- session.prepare(statements.Delete)
    } yield new LockStoreImpl(
      session = session,
      insertIfNotExistsSt = insertIfNotExistsSt,
      deleteSt = deleteSt,
    )
  }

  /**
   * Lock row TTL in seconds for the given `expiry`, see [[LockStore.insertIfNotExists]].
   */
  def ttlSeconds(expiry: FiniteDuration): Int = {
    val nanosInSecond: Long = 1_000_000_000L

    val nanos: Long = math.max(expiry.toNanos, nanosInSecond)
    // use Math.ceilDiv when switched to JDK 18+
    val secondsLong = nanos / nanosInSecond + math.signum(nanos % nanosInSecond)
    // this one prevents overflow going into negative values
    math.min(secondsLong, Int.MaxValue.toLong).toInt
  }
}

/**
 * Lock table CQL statements.
 *
 * @param keyspace
 *   keyspace name
 * @param table
 *   table name
 */
private[sync] final class LockStatements(keyspace: String, table: String) {
  val CreateTableIfNotExists: String =
    s"CREATE TABLE IF NOT EXISTS $keyspace.$table (" +
      "id text PRIMARY KEY, " +
      "expiry_ms BIGINT, " +
      "timestamp TIMESTAMP," +
      "metadata TEXT)"

  val InsertIfNotExists: String =
    s"INSERT INTO $keyspace.$table (id, expiry_ms, timestamp, metadata) VALUES (?, ?, ?, ?) IF NOT EXISTS USING TTL ?"

  val Delete: String =
    /*
      DELETE should also be a LWT op as mixing LWT and normal ops is prohibited:
      https://docs.datastax.com/en/ddac/doc/datastax_enterprise/dbInternals/dbIntLtwtTransactions.html

      Lightweight transactions will block other lightweight transactions from occurring,
      but will not stop normal read and write operations from occurring.
      Lightweight transactions use a timestamping mechanism different from normal operations,
      so mixing lightweight transactions and normal operations can result in errors.
      If lightweight transactions are used to write to a row within a partition,
      only lightweight transactions for both read and write operations should be used.
      This caution applies to all operations, whether individual or batched.
     */
    s"DELETE FROM $keyspace.$table WHERE id = ? IF EXISTS"
}

private final class LockStoreImpl[F[_]: Sync](
  session: CassandraSession[F],
  insertIfNotExistsSt: PreparedStatement,
  deleteSt: PreparedStatement,
) extends LockStore[F] {

  override def insertIfNotExists(
    id: String,
    expiry: FiniteDuration,
    timestamp: Instant,
    metadata: Option[String],
  ): F[Boolean] = {
    runPreparedStatement {
      insertIfNotExistsSt
        .bind()
        .encode("id", id)
        .encode("expiry_ms", expiry.toMillis)
        .encode("timestamp", timestamp)
        .encode("metadata", metadata)
        .encode("[ttl]", LockStore.ttlSeconds(expiry))
        // TODO: https://github.com/evolution-gaming/cassandra-sync/issues/273 is not really idempotent
        // if insert times out but was really inserted on the server, the second attempt will return false
        // while it should've been true which might stall the locking process
        .setIdempotent(true)
    }
  }.map(_.wasApplied())

  override def delete(id: String): F[Unit] = {
    runPreparedStatement {
      deleteSt
        .bind()
        .encode("id", id)
        .setIdempotent(true)
    }.void
  }

  private def runPreparedStatement(thunk: => BoundStatement): F[AsyncResultSet] = {
    for {
      // wrapping in delay because driver's statement building code might throw exceptions
      boundSt <- Sync[F].delay {
        thunk
      }
      result <- session.execute(boundSt)
    } yield result
  }
}
