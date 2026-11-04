package com.evolution.cassandra.sync.impl

import cats.effect.*
import cats.effect.std.MapRef
import cats.implicits.*

import java.time.Instant

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

  override def insertAcquire(lock: Lock): IO[Option[AcquiredLock]] = {
    for {
      now <- IO.realTimeInstant
      acquired <- rowMapRef(lock.id).modify[Option[AcquiredLock]] {
        case Some(existingRow) if existingRow.isAlive(now) =>
          val acquired = Option.when(existingRow.lock.acquisitionId == lock.acquisitionId)(AcquiredLock.fromLock(lock))
          existingRow.some -> acquired
        case _ =>
          val newRow = LockRow(
            lock = lock,
            expiresAt = now.plusSeconds(LockStore.ttlSeconds(lock.expiry).toLong),
          )
          newRow.some -> AcquiredLock.fromLock(lock).some
      }
    } yield acquired
  }

  override def deleteIfAcquired(acquiredLock: AcquiredLock): IO[Boolean] = {
    for {
      now <- IO.realTimeInstant
      deleted <- rowMapRef(acquiredLock.lockId).modify[Boolean] {
        case Some(row) if row.isAlive(now) && row.lock.acquisitionId == acquiredLock.acquisitionId =>
          none[LockRow] -> true
        case rowOpt =>
          rowOpt -> false
      }
    } yield deleted
  }

  /**
   * Returns a non-expired lock by ID.
   */
  def get(id: String): IO[Option[Lock]] = {
    for {
      now <- IO.realTimeInstant
      rowOpt <- rowMapRef(id).get
    } yield rowOpt.filter(_.isAlive(now)).map(_.lock)
  }
}

private[sync] object InMemoryLockStore {

  private final case class LockRow(
    lock: Lock,
    expiresAt: Instant,
  ) {
    def isAlive(now: Instant): Boolean = now.isBefore(expiresAt)
  }

  def make: IO[InMemoryLockStore] = {
    MapRef[IO, String, LockRow].map(new InMemoryLockStore(_))
  }
}
