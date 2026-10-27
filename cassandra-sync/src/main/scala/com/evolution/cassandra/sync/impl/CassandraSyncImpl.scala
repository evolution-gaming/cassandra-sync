package com.evolution.cassandra.sync.impl

import cats.effect.*
import cats.implicits.*
import com.evolution.cassandra.sync.*

import java.time.Instant
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

    def lock(timestamp: Instant): Resource[F, Unit] = {

      val deadline = timestamp.toEpochMilli + timeout.toMillis

      def timeoutError = LockAcquireTimeoutError(timeout)

      val checkDeadline = for {
        now <- Clock[F].realTimeInstant
        _ <- if (now.toEpochMilli > deadline) timeoutError.raiseError[F, Instant] else now.pure[F]
      } yield now

      def retry: F[Instant] = for {
        _ <- checkDeadline
        _ <- Temporal[F].sleep(pollInterval)
        timestamp <- checkDeadline
      } yield timestamp

      val lock = timestamp.tailRecM { timestamp =>
        for {
          // TODO: https://github.com/evolution-gaming/cassandra-sync/issues/273
          // - with migration to Cassandra driver 4, the default query timeout goes from 12 seconds to 3
          // - handle LWT op timeouts gracefully
          applied <- lockStore.insertIfNotExists(id, expiry, timestamp, metadata)
          result <- if (applied) ().asRight[Instant].pure[F] else retry.map(_.asLeft[Unit])
          _ <- Temporal[F].sleep(pollInterval)
        } yield result
      }

      val unlock = lockStore.delete(id)

      val result = for {
        a <- lock
      } yield {
        (a, unlock)
      }
      Resource(result)
    }

    for {
      timestamp <- Clock[F].realTimeInstant
      result <- lock(timestamp).use { _ => f }
    } yield result
  }
}
