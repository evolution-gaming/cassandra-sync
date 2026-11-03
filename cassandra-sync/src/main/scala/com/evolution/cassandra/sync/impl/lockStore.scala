package com.evolution.cassandra.sync.impl

import cats.*
import cats.implicits.*
import com.datastax.oss.driver.api.core.cql.*
import com.datastax.oss.driver.api.core.{ConsistencyLevel, CqlIdentifier}
import com.evolution.scassandra.*
import com.evolution.scassandra.syntax.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/**
 * Cassandra lock row data for [[LockStore]].
 *
 * @param id
 *   lock ID
 * @param acquisitionId
 *   identifies the lock acquisition - one acquire and its corresponding release operations
 * @param expiry
 *   lock row expiration TTL, rounded up to seconds, if < 1s, 1s TTL is used
 * @param timestamp
 *   acquisition timestamp
 * @param metadata
 *   user provided lock metadata
 */
private[sync] final case class Lock(
  id: String,
  acquisitionId: UUID,
  expiry: FiniteDuration,
  timestamp: Instant,
  metadata: Option[String],
)

/**
 * Acquired lock data for [[LockStore]].
 *
 * Contains information required to safely release the lock.
 *
 * @param lockId
 *   see [[Lock.id]]
 * @param acquisitionId
 *   see [[Lock.acquisitionId]]
 * @param metadata
 *   see [[Lock.metadata]]
 */
private[sync] final case class AcquiredLock(
  lockId: String,
  acquisitionId: UUID,
  metadata: Option[String], // needed only for LockStoreImplV4
)
private[sync] object AcquiredLock {
  def fromLock(lock: Lock): AcquiredLock = AcquiredLock(
    lockId = lock.id,
    acquisitionId = lock.acquisitionId,
    metadata = lock.metadata,
  )
}

/**
 * Lock storage abstraction over Cassandra for [[CassandraSyncImpl]].
 *
 * All operations here are meant to be executed with LWTs thus having linearizable guarantees per
 * lock row.
 */
private[sync] trait LockStore[F[_]] {

  /**
   * Inserts a lock row acquiring it, if it hasn't been before.
   *
   * The lock is logically identified by ID. On INSERT IF NOT EXISTS conflict,
   * [[Lock.acquisitionId]] for the existing row is checked to determine whether lock was actually
   * acquired or not.
   *
   * Returns [[AcquiredLock]] if it was acquired, [[None]] if another acquisition process has
   * already taken it.
   */
  def insertAcquire(lock: Lock): F[Option[AcquiredLock]]

  /**
   * Deletes an acquired lock row, checking if it's still acquired.
   *
   * What has to match:
   *   - lock ID
   *   - acquisition ID
   *
   * Returns true if a matching lock row was deleted, false otherwise.
   */
  def deleteIfAcquired(acquiredLock: AcquiredLock): F[Boolean]
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
  def of[F[_]: MonadThrow](
    session: CassandraSession[F],
    statements: LockStatements,
    keyspace: String,
    table: String,
  ): F[LockStore[F]] = {
    hasAcquisitionIdColumn(session, keyspace = keyspace, table = table).flatMap { acquisitionIdColumnExists =>
      if (acquisitionIdColumnExists) {
        // use post-V5 schema implementation
        for {
          insertIfNotExistsSt <- session.prepare(statements.InsertIfNotExists)
          deleteIfAcquiredSt <- session.prepare(statements.DeleteIfAcquired)
        } yield new LockStoreImpl[F](
          session,
          insertIfNotExistsSt = insertIfNotExistsSt,
          deleteIfAcquiredSt = deleteIfAcquiredSt,
        )
      } else {
        // use pre-V5 schema implementation
        for {
          insertIfNotExistsSt <- session.prepare(statements.InsertIfNotExistsV4)
          deleteIfAcquiredSt <- session.prepare(statements.DeleteIfAcquiredV4)
        } yield new LockStoreImplV4[F](
          session,
          insertIfNotExistsSt = insertIfNotExistsSt,
          deleteIfAcquiredSt = deleteIfAcquiredSt,
        )
      }
    }
  }

  /**
   * Checks whether the locks table has the `acquisition_id` column, i.e. has the current schema.
   *
   * Asks the server instead of using the driver's schema metadata, which might be stale, filtered
   * or disabled.
   *
   * Returns false if the table does not exist, preparing the lock statements fails then.
   */
  private def hasAcquisitionIdColumn[F[_]: MonadThrow](
    session: CassandraSession[F],
    keyspace: String,
    table: String,
  ): F[Boolean] = {
    for {
      statement <- MonadThrow[F].catchNonFatal {
        SimpleStatement
          .newInstance(
            "SELECT column_name FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ? AND column_name = ?",
            // the names are given as in CQL statements, while schema tables store them in the internal form
            CqlIdentifier.fromCql(keyspace).asInternal(),
            CqlIdentifier.fromCql(table).asInternal(),
            "acquisition_id",
          )
          // schema tables are local to each node, and some consistency levels, like EACH_QUORUM, fail for reads
          .setConsistencyLevel(ConsistencyLevel.ONE)
          .setIdempotent(true)
      }
      resultSet <- session.execute(statement)
    } yield Option(resultSet.one()).isDefined
  }

  /**
   * Lock row TTL in seconds for the given `expiry`, see [[LockStore.insertAcquire]].
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
      "acquisition_id UUID, " +
      "expiry_ms BIGINT, " +
      "timestamp TIMESTAMP," +
      "metadata TEXT)"

  /*
    All operations on the table here should be LWTs op as mixing LWT and normal ops is prohibited:
    https://docs.datastax.com/en/ddac/doc/datastax_enterprise/dbInternals/dbIntLtwtTransactions.html
  
    Lightweight transactions will block other lightweight transactions from occurring,
    but will not stop normal read and write operations from occurring.
    Lightweight transactions use a timestamping mechanism different from normal operations,
    so mixing lightweight transactions and normal operations can result in errors.
    If lightweight transactions are used to write to a row within a partition,
    only lightweight transactions for both read and write operations should be used.
    This caution applies to all operations, whether individual or batched.
   */

  val InsertIfNotExists: String =
    s"INSERT INTO $keyspace.$table (id, acquisition_id, expiry_ms, timestamp, metadata) VALUES (?, ?, ?, ?, ?) IF NOT EXISTS USING TTL ?"

  val InsertIfNotExistsV4: String =
    s"INSERT INTO $keyspace.$table (id, expiry_ms, timestamp, metadata) VALUES (?, ?, ?, ?) IF NOT EXISTS USING TTL ?"

  val DeleteIfAcquired: String =
    s"DELETE FROM $keyspace.$table WHERE id = ? IF acquisition_id = ?"

  val DeleteIfAcquiredV4: String =
    s"DELETE FROM $keyspace.$table WHERE id = ? IF metadata = ?"
}

private final class LockStoreImpl[F[_]: MonadThrow](
  session: CassandraSession[F],
  insertIfNotExistsSt: PreparedStatement,
  deleteIfAcquiredSt: PreparedStatement,
) extends LockStore[F] {

  override def insertAcquire(lock: Lock): F[Option[AcquiredLock]] = {
    runPreparedStatement {
      insertIfNotExistsSt
        .bind()
        .encode("id", lock.id)
        .encode("acquisition_id", lock.acquisitionId)
        .encode("expiry_ms", lock.expiry.toMillis)
        .encode("timestamp", lock.timestamp)
        .encode("metadata", lock.metadata)
        .encode("[ttl]", LockStore.ttlSeconds(lock.expiry))
        // can be retried by the driver because we are checking the returned existing row on conflicts
        .setIdempotent(true)
    }
  }.map { rs =>
    if (rs.wasApplied()) {
      AcquiredLock.fromLock(lock).some
    } else {
      val alreadyExistingRow = Option(rs.one()).getOrElse(sys.error(
        "no row returned on INSERT IF NOT EXISTS conflict",
      ))
      // optional because the schema might already contain the column, but we might be dealing with a row inserted by
      // an old cassandra-sync version still
      val alreadyExistingAcquisitionIdOpt = alreadyExistingRow.decode[Option[UUID]]("acquisition_id")
      if (alreadyExistingAcquisitionIdOpt.contains(lock.acquisitionId)) {
        AcquiredLock.fromLock(lock).some
      } else {
        None
      }
    }
  }

  override def deleteIfAcquired(acquiredLock: AcquiredLock): F[Boolean] = {
    runPreparedStatement {
      deleteIfAcquiredSt
        .bind()
        .encode("id", acquiredLock.lockId)
        .encode("acquisition_id", acquiredLock.acquisitionId)
    }.map(_.wasApplied())
  }

  private def runPreparedStatement(thunk: => BoundStatement): F[AsyncResultSet] = {
    for {
      // wrapping in catchNonFatal because driver's statement building code might throw exceptions
      boundSt <- MonadThrow[F].catchNonFatal {
        thunk
      }
      result <- session.execute(boundSt)
    } yield result
  }
}

/**
 * [[LockStore]] implementation which works with pre-5.0.0 locks table schema - without a dedicated
 * acquisition_id column.
 *
 * To circumvent this, the implementation encodes acquisition ID value into the metadata column and
 * uses it for conditional statements.
 */
private final class LockStoreImplV4[F[_]: MonadThrow](
  session: CassandraSession[F],
  insertIfNotExistsSt: PreparedStatement,
  deleteIfAcquiredSt: PreparedStatement,
) extends LockStore[F] {
  override def insertAcquire(lock: Lock): F[Option[AcquiredLock]] = {
    val effectiveMetadata = getEffectiveMetadata(lock)

    runPreparedStatement {
      insertIfNotExistsSt
        .bind()
        .encode("id", lock.id)
        .encode("expiry_ms", lock.expiry.toMillis)
        .encode("timestamp", lock.timestamp)
        .encode("metadata", effectiveMetadata)
        .encode("[ttl]", LockStore.ttlSeconds(lock.expiry))
        .setIdempotent(true)
    }.map { rs =>
      if (rs.wasApplied()) {
        AcquiredLock.fromLock(lock).some
      } else {
        val alreadyExistingRow = Option(rs.one()).getOrElse(sys.error(
          "no row returned on INSERT IF NOT EXISTS conflict",
        ))
        val alreadyExistingMetadataOpt = alreadyExistingRow.decode[Option[String]]("metadata")
        if (alreadyExistingMetadataOpt.contains(effectiveMetadata)) {
          AcquiredLock.fromLock(lock).some
        } else {
          None
        }
      }
    }
  }

  override def deleteIfAcquired(acquiredLock: AcquiredLock): F[Boolean] = {
    runPreparedStatement {
      deleteIfAcquiredSt
        .bind()
        .encode("id", acquiredLock.lockId)
        .encode("metadata", getEffectiveMetadata(acquiredLock))
    }.map(_.wasApplied())
  }

  private def runPreparedStatement(thunk: => BoundStatement): F[AsyncResultSet] = {
    for {
      // wrapping in catchNonFatal because driver's statement building code might throw exceptions
      boundSt <- MonadThrow[F].catchNonFatal {
        thunk
      }
      result <- session.execute(boundSt)
    } yield result
  }

  private def getEffectiveMetadata(acquisitionId: UUID, metadata: Option[String]): String = {
    // UUID toString has fixed length and doesn't contain the delimiting symbol '|'
    // so this string representation of (acquisitionId, metadata) is reversible
    (Vector(acquisitionId.toString) ++ metadata).mkString("|")
  }

  private def getEffectiveMetadata(lock: Lock): String = {
    getEffectiveMetadata(acquisitionId = lock.acquisitionId, metadata = lock.metadata)
  }

  private def getEffectiveMetadata(acquiredLock: AcquiredLock): String = {
    getEffectiveMetadata(acquisitionId = acquiredLock.acquisitionId, metadata = acquiredLock.metadata)
  }
}
