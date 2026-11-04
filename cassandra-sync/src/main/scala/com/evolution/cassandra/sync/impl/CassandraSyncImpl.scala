package com.evolution.cassandra.sync.impl

import cats.*
import cats.effect.*
import cats.effect.implicits.*
import cats.implicits.*
import com.evolution.cassandra.sync.*
import com.evolutiongaming.catshelper.Log

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/**
 * Default implementation for [[CassandraSync]].
 *
 * @param lockStore
 *   see [[LockStore]]
 * @param pollInterval
 *   interval between lock acquisition poll attempts
 * @param log
 *   log for lock release problems, which are not propagated to the caller
 */
private[sync] final class CassandraSyncImpl[F[_]: Async](
  lockStore: LockStore[F],
  pollInterval: FiniteDuration,
  log: Log[F],
) extends CassandraSync[F] {

  def apply[A](
    id: String,
    expiry: FiniteDuration,
    timeout: FiniteDuration,
    metadata: Option[String],
  )(
    f: => F[A],
  ): F[A] = {

    // Only waiting between attempts is cancelable. An insert canceled mid-flight might still get
    // applied by Cassandra, and then the lock row would stay unreleased until it expires.
    def acquire(poll: Poll[F]): F[AcquiredLock] = {
      Sync[F].delay { UUID.randomUUID() }.flatMap { acquisitionId =>
        Monad[F].tailRecM(()) { _ =>
          for {
            lockTimestamp <- Clock[F].realTimeInstant
            lock = Lock(id = id, acquisitionId = acquisitionId, expiry, timestamp = lockTimestamp, metadata = metadata)
            acquireResult <- lockStore.insertAcquire(lock)
            continueOrStop <-
              acquireResult match {
                case Some(acquiredLock) =>
                  Applicative[F].pure(acquiredLock.asRight[Unit])
                case None =>
                  poll(Temporal[F].sleep(pollInterval)).as(().asLeft[AcquiredLock])
              }
          } yield continueOrStop
        }
      }
    }

    // Release problems are logged instead of being raised: a release error would replace the result of successful `f`,
    // making the caller think `f` has failed and possibly rerun it, while the lock row expires by TTL anyway.
    def release(acquired: AcquiredLock): F[Unit] = {
      lockStore.deleteIfAcquired(acquired).attempt.flatMap {
        case Right(true) =>
          Applicative[F].unit
        case Right(false) =>
          // the row is either gone or belongs to another acquisition now
          log.warn(
            s"lock $id (acquisition ${ acquired.acquisitionId }) was no longer held on release, " +
              s"most likely it expired before the protected section completed, consider increasing expiry ($expiry)",
          )
        case Left(error) =>
          log.error(
            s"failed to release lock $id (acquisition ${ acquired.acquisitionId }), " +
              s"it might stay held until it expires ($expiry)",
            error,
          )
      }
    }

    val lockResource = Resource
      .makeFull(acquire)(release)
      // Resource.timeout applies only to acquire, cancelling it if the time runs out.
      // It has to be applied directly to makeFull: a map or void in between adds a cancellation point after
      // the uncancelable acquire, so a lock acquired by an insert in flight at the timeout would be released
      // with LockAcquireTimeoutError raised instead of running `f`.
      .timeoutTo(timeout, Resource.raiseError[F, AcquiredLock, Throwable](LockAcquireTimeoutError(timeout)))

    // not using surround(f) here because CassandraSync.apply f is a by-name parameters and surround would cause its
    // eager evaluation before the lock is acquired
    lockResource.use(_ => f)
  }
}
