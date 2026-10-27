package com.evolution.cassandra.sync.impl

import cats.effect.*
import cats.effect.std.MapRef
import cats.implicits.*

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
 * In-memory [[LockStore]] for unit tests.
 *
 * Mimics the Cassandra table TTL by treating lock rows as absent once the TTL derived from `expiry`
 * (see [[LockStore.ttlSeconds]]) has passed since the insertion, according to `IO.realTimeInstant`.
 */
private[sync] final class InMemoryLockStore private (
  rowMapRef: MapRef[IO, String, Option[InMemoryLockStore.LockRow]],
) extends LockStore[IO] {
  import InMemoryLockStore.*

  override def insertIfNotExists(
    id: String,
    expiry: FiniteDuration,
    timestamp: Instant,
    metadata: Option[String],
  ): IO[Boolean] = {
    for {
      now <- IO.realTimeInstant
      applied <- rowMapRef(id).modify { rowOpt =>
        if (rowOpt.exists(_.isAlive(now))) {
          rowOpt -> false
        } else {
          val newRow = LockRow(
            data = LockData(
              expiry = expiry,
              timestamp = timestamp,
              metadata = metadata,
            ),
            expiresAt = now.plusSeconds(LockStore.ttlSeconds(expiry).toLong),
          )
          newRow.some -> true
        }
      }
    } yield applied
  }

  override def delete(id: String): IO[Unit] = rowMapRef(id).set(None)

  /**
   * Returns a non-expired lock data by ID.
   */
  def get(id: String): IO[Option[LockData]] = {
    for {
      now <- IO.realTimeInstant
      rowOpt <- rowMapRef(id).get
    } yield rowOpt.filter(_.isAlive(now)).map(_.data)
  }
}

private[sync] object InMemoryLockStore {

  final case class LockData(
    expiry: FiniteDuration,
    timestamp: Instant,
    metadata: Option[String],
  )

  private final case class LockRow(
    data: LockData,
    expiresAt: Instant,
  ) {
    def isAlive(now: Instant): Boolean = now.isBefore(expiresAt)
  }

  def make: IO[InMemoryLockStore] = {
    MapRef[IO, String, LockRow].map(new InMemoryLockStore(_))
  }
}
