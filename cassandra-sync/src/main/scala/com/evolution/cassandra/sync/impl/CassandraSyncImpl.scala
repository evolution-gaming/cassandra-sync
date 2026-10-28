package com.evolution.cassandra.sync.impl

import cats.*
import cats.effect.*
import cats.effect.implicits.*
import cats.implicits.*
import com.evolution.cassandra.sync.*

import scala.concurrent.duration.FiniteDuration

/**
 * Default implementation for [[CassandraSync]].
 *
 * @param lockStore
 *   see [[LockStore]]
 * @param pollInterval
 *   interval between lock acquisition poll attempts
 */
private[sync] final class CassandraSyncImpl[F[_]: Temporal](
  lockStore: LockStore[F],
  pollInterval: FiniteDuration,
) extends CassandraSync[F] {

  def apply[A](
    id: String,
    expiry: FiniteDuration,
    timeout: FiniteDuration,
    metadata: Option[String],
  )(
    f: => F[A],
  ): F[A] = {
    val release: F[Unit] = lockStore.delete(id)

    // Only waiting between attempts is cancelable. An insert canceled mid-flight might still get
    // applied by Cassandra, and then the lock row would stay unreleased until it expires.
    def acquire(poll: Poll[F]): F[Unit] = Monad[F].tailRecM(()) { _ =>
      for {
        lockTimestamp <- Clock[F].realTimeInstant
        // TODO: https://github.com/evolution-gaming/cassandra-sync/issues/273
        // - with migration to Cassandra driver 4, the default query timeout goes from 12 seconds to 3
        // - handle LWT op timeouts gracefully
        applied <- lockStore.insertIfNotExists(id, expiry, lockTimestamp, metadata)
        continueOrStop <-
          if (applied) {
            Applicative[F].pure(().asRight[Unit])
          } else {
            poll(Temporal[F].sleep(pollInterval)).as(().asLeft[Unit])
          }
      } yield continueOrStop
    }

    val lockResource = Resource
      .makeFull(acquire)(_ => release)
      // Resource.timeout applies only to acquire, cancelling it if the time runs out
      .timeoutTo(timeout, Resource.raiseError[F, Unit, Throwable](LockAcquireTimeoutError(timeout)))

    // not using surround(f) here because CassandraSync.apply f is a by-name parameters and surround would cause its
    // eager evaluation before the lock is acquired
    lockResource.use(_ => f)
  }
}
