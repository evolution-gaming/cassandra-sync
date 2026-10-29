package com.evolution.cassandra.sync

import cats.effect.*
import cats.implicits.*
import cats.~>
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.evolution.cassandra.sync.impl.{CassandraSyncImpl, LockStatements, LockStore}
import com.evolution.scassandra.*

import scala.concurrent.duration.*

/**
 * Distributed lock implemented using Cassandra lightweight transactions.
 *
 * The main use case is serializing actions which must not run concurrently across multiple
 * application nodes, such as Cassandra schema changes.
 *
 * Use [[CassandraSync.of]] to create an instance:
 * {{{
 * for {
 *   cassandraSync <- CassandraSync.of[IO](session, keyspace = "app")
 *   result <- cassandraSync(id = "migrate-schema") {
 *     // runs strictly sequentially with other sections protected by the same lock ID
 *     migrateSchema
 *   }
 * } yield result
 * }}}
 */
trait CassandraSync[F[_]] {

  /*
  TODO: https://github.com/evolution-gaming/cassandra-sync/issues/269
  Refactor API to look like CE mutex?
  deprecating the apply method
  
  def run: IO[Unit] = {
    Mutex[IO].flatMap { mutex =>
      // Wrap the action inside the lock
      def securedAction(id: Int): IO[Unit] =
        mutex.lock.surround(criticalAction(id))

      // Run multiple tasks concurrently to demonstrate serial execution
      List(1, 2, 3)
        .parTraverse(id => securedAction(id))
        .void
    }
  }
   */

  /**
   * Acquires the lock by ID, runs `f` and releases the lock once `f` terminates, fails or gets
   * interrupted.
   *
   * If the lock is held by someone else, acquisition is retried until it succeeds or `timeout`
   * passes. In the latter case the resulting effect fails with [[LockAcquireTimeoutError]] and `f`
   * is not run.
   *
   * Please note that `expiry` timeout should be larger than `f` expected max run time. In case if
   * lock expiration hits before protected sections finished, another contender could take the same
   * lock.
   *
   * The lock is not reentrant: acquiring a lock with the same ID from within `f` will not succeed
   * until the outer lock expires.
   *
   * @param id
   *   lock id
   * @param expiry
   *   when to expire the lock in case it was not removed gracefully, e.g. if JVM crashed; the value
   *   is translated into a Cassandra lock row expiration TTL, which is rounded up to seconds, if <
   *   1s, 1s TTL is used;
   * @param timeout
   *   time given for acquiring the lock before [[LockAcquireTimeoutError]] is raised, it doesn't
   *   limit the run time of `f`
   * @param metadata
   *   you can provide additional information for tracing origin of lock
   * @param f
   *   the action to run while holding the lock
   * @return
   *   result of `f`
   */
  def apply[A](
    id: String,
    expiry: FiniteDuration = 1.minute,
    timeout: FiniteDuration = 1.minute,
    metadata: Option[String] = None,
  )(
    f: => F[A],
  ): F[A]
}

/**
 * Factory methods and syntax extensions for [[CassandraSync]].
 */
object CassandraSync {

  /**
   * Creates a [[CassandraSync]] instance storing locks in `keyspace.table`.
   *
   * Depending on `autoCreate`, the keyspace and the table are created if they don't exist yet. With
   * [[AutoCreate.None]] the table has to exist before calling this method, as lock statements are
   * prepared during creation.
   *
   * Please note that concurrent schema changes are not supported by Cassandra, so creating the
   * table from several application nodes at once might fail. It is better to create the table in
   * advance, see the table definition in the README.
   *
   * @param session
   *   Cassandra session used to create the schema and to run lock queries
   * @param keyspace
   *   keyspace of the locks table
   * @param table
   *   name of the locks table
   * @param autoCreate
   *   whether to create the table and, optionally, the keyspace, see [[AutoCreate]]
   * @param interval
   *   interval between lock acquisition attempts while the lock is held by someone else
   */
  def of[F[_]: Async](
    session: CassandraSession[F],
    keyspace: String,
    table: String = "locks",
    autoCreate: AutoCreate = AutoCreate.None,
    interval: FiniteDuration = 100.millis,
  ): F[CassandraSync[F]] = {

    val statements = new LockStatements(keyspace = keyspace, table = table)

    def createTable: F[Unit] = session.execute(statements.CreateTableIfNotExists).void

    def createKeyspace(replicationStrategy: ReplicationStrategyConfig): F[AsyncResultSet] = {
      val query = CreateKeyspaceIfNotExists(keyspace, replicationStrategy)
      session.execute(query)
    }

    def createTableAndKeyspace(replicationStrategy: ReplicationStrategyConfig): F[Unit] = for {
      _ <- createKeyspace(replicationStrategy)
      _ <- createTable
    } yield {}

    val created = autoCreate match {
      case AutoCreate.None => ().pure[F]
      case AutoCreate.Table => createTable
      case a: AutoCreate.KeyspaceAndTable => createTableAndKeyspace(a.replicationStrategy)
    }

    for {
      _ <- created
      lockStore <- LockStore.of(session, statements)
    } yield new CassandraSyncImpl[F](lockStore, pollInterval = interval)
  }

  /**
   * Syntax extensions for [[CassandraSync]]
   */
  implicit class CassandraSyncOps[F[_]](val self: CassandraSync[F]) extends AnyVal {

    /**
     * Changes the effect type of [[CassandraSync]] from `F` to `G`.
     *
     * Both directions are required, as the protected action given in `G` has to be run in `F` under
     * the lock.
     *
     * @param f
     *   converts the locked section, including lock acquisition and release, from `F` to `G`
     * @param g
     *   converts the protected action from `G` to `F`
     */
    def mapK[G[_]](f: F ~> G, g: G ~> F): CassandraSync[G] = new CassandraSync[G] {

      def apply[A](
        id: String,
        expiry: FiniteDuration,
        timeout: FiniteDuration,
        metadata: Option[String],
      )(
        f1: => G[A],
      ): G[A] = {

        f(self(id, expiry, timeout, metadata)(g(f1)))
      }
    }
  }
}
