package com.evolution.cassandra.sync.impl

import cats.effect.*
import cats.effect.testkit.TestControl
import cats.implicits.*
import com.evolution.cassandra.sync.{CassandraSync, LockAcquireTimeoutError}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

/**
 * Verifies [[CassandraSyncImpl]] logic without using Cassandra.
 *
 * Uses [[InMemoryLockStore]].
 */
class CassandraSyncImplSpec extends AnyWordSpec with Matchers {
  import CassandraSyncImplSpec.*

  "CassandraSyncImpl" should {

    "run the function while holding the lock and release it afterwards" in runTestControl {
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(store)
        startTime <- IO.realTimeInstant
        lockDataWhenTaken <- sync(Id, expiry = 10.seconds, metadata = "metadata".some)(store.get(Id))
        lockDataAfterRelease <- store.get(Id)
      } yield {
        // comparing user provided lock data (without acquisition ID)
        lockDataWhenTaken.map(lock => (lock.id, lock.expiry, lock.timestamp, lock.metadata)) shouldEqual
          (Id, 10.seconds, startTime, "metadata".some).some
        lockDataAfterRelease shouldEqual None
      }
    }

    "release the lock if the function fails" in runTestControl {
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(store)
        result <- sync(Id)(IO.raiseError[Unit](TestError)).attempt
        lockDataAfterRelease <- store.get(Id)
      } yield {
        result shouldEqual TestError.asLeft
        lockDataAfterRelease shouldEqual None
      }
    }

    "release the lock if the function is canceled" in runTestControl {
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(store)
        lockHoldingFiber <- sync(Id)(IO.never[Unit]).start
        _ <- IO.sleep(1.second)
        lockDataBeforeCancel <- store.get(Id)
        _ <- lockHoldingFiber.cancel
        lockHoldingFiberOutcome <- lockHoldingFiber.join
        lockDataAfterCancel <- store.get(Id)
      } yield {
        lockDataBeforeCancel shouldBe defined
        lockHoldingFiberOutcome.isCanceled shouldBe true
        lockDataAfterCancel shouldEqual None
      }
    }

    "be cancelable while waiting for the lock" in runTestControl {
      val timeout = 1.minute
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(store)
        otherHolderLock <- acquireByOtherHolder(store, expiry = 1.hour)
        fStarted <- Ref.of[IO, Boolean](false)
        waitingFiber <- sync(Id, timeout = timeout)(fStarted.set(true)).start
        _ <- IO.sleep(1.second)
        cancelElapsedTimeWithUnit <- waitingFiber.cancel.timed
        waitingFiberOutcome <- waitingFiber.join
        fStartedResult <- fStarted.get
        lockDataAfterCancel <- store.get(Id)
      } yield {
        val (cancelElapsedTime, _) = cancelElapsedTimeWithUnit
        // cancellation should interrupt polling instead of waiting for the timeout
        cancelElapsedTime.toCoarsest should be <= PollInterval
        waitingFiberOutcome.isCanceled shouldBe true
        fStartedResult shouldBe false
        // the lock of the current holder must not be removed
        lockDataAfterCancel shouldEqual otherHolderLock.some
      }
    }

    "release the lock if canceled while the lock insert is in flight" in runTestControl {
      for {
        store <- InMemoryLockStore.make
        fStarted <- Ref.of[IO, Boolean](false)
        acquiringFiber <- cassandraSync(slowInsertLockStore(store, 1.second))(Id)(fStarted.set(true)).start
        _ <- IO.sleep(500.millis)
        _ <- acquiringFiber.cancel
        acquiringFiberOutcome <- acquiringFiber.join
        fStartedResult <- fStarted.get
        lockDataAfterCancel <- store.get(Id)
      } yield {
        acquiringFiberOutcome.isCanceled shouldBe true
        fStartedResult shouldBe false
        lockDataAfterCancel shouldEqual None
      }
    }

    "wait for the lock to be released by the current holder" in runTestControl {
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(store)
        firstLockHolderReleaseDeferred <- Deferred[IO, Unit]
        secondStartedFlagRef <- Ref.of[IO, Boolean](false)
        firstLockHolderFiber <- sync(Id)(firstLockHolderReleaseDeferred.get.as("first")).start
        _ <- IO.sleep(1.second)
        secondLockHolderFiber <- sync(Id)(secondStartedFlagRef.set(true).as("second")).start
        _ <- IO.sleep(10.seconds)
        secondStartedBeforeRelease <- secondStartedFlagRef.get
        _ <- firstLockHolderReleaseDeferred.complete(())
        firstLockHolderResult <- firstLockHolderFiber.joinWithNever
        secondLockHolderResult <- secondLockHolderFiber.joinWithNever
      } yield {
        secondStartedBeforeRelease shouldBe false
        firstLockHolderResult shouldEqual "first"
        secondLockHolderResult shouldEqual "second"
      }
    }

    "run concurrent functions with the same ID one at a time" in runTestControl {
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(store)
        currentLockHoldersCountRef <- Ref.of[IO, Int](0)
        maxSimultaneousLockHoldersCountRef <- Ref.of[IO, Int](0)
        underLockProgram = for {
          n <- currentLockHoldersCountRef.updateAndGet(_ + 1)
          _ <- maxSimultaneousLockHoldersCountRef.update(_ max n)
          _ <- IO.sleep(1.second)
          _ <- currentLockHoldersCountRef.update(_ - 1)
        } yield ()
        _ <- ArraySeq.fill(10)(sync(Id)(underLockProgram)).parSequence_
        maxActiveResult <- maxSimultaneousLockHoldersCountRef.get
      } yield {
        maxActiveResult shouldEqual 1
      }
    }

    "not block functions with different IDs" in runTestControl {
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(store)
        firstLockHolderReleaseDeferred <- Deferred[IO, Unit]
        firstLockHolderFiber <- sync("id-1")(firstLockHolderReleaseDeferred.get).start
        _ <- IO.sleep(1.second)
        secondLockHolderResult <- sync("id-2")(IO.pure("second"))
        firstLockData <- store.get("id-1")
        _ <- firstLockHolderReleaseDeferred.complete(())
        _ <- firstLockHolderFiber.joinWithNever
      } yield {
        secondLockHolderResult shouldEqual "second"
        firstLockData shouldBe defined
      }
    }

    "acquire the lock once it expires if the holder never releases it" in runTestControl {
      val expiry = 5.seconds
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(store)
        startTime <- IO.realTimeInstant
        _ <- acquireByOtherHolder(store, expiry)
        acquiredAtWithLockData <- sync(Id)((IO.realTimeInstant, store.get(Id)).tupled)
      } yield {
        val (acquiredAt, lockData) = acquiredAtWithLockData
        val expiredAt = startTime.plusMillis(expiry.toMillis)
        acquiredAt.isBefore(expiredAt) shouldBe false
        // the timestamp is refreshed on retries instead of being kept from the first attempt
        lockData.map(_.timestamp.isAfter(startTime)) shouldEqual true.some
      }
    }

    "not release the lock if it has expired and another caller has acquired it" in {
      val program: IO[String] = for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(store)
        _ <- sync(Id, expiry = 2.seconds) {
          IO.sleep(6.seconds)
        }.start
        _ <- IO.sleep(3.seconds)
        // first lock row has expired, but the first lock body is still running
        // second lock should acquire right away
        _ <- sync(Id) {
          IO.sleep(5.seconds)
        }.start
        _ <- IO.sleep(1.seconds) // giving the second lock holder time to acquire
        thirdLockHolderFiber <- sync(Id) {
          IO.pure("third lock acquired")
        }.start
        result <- thirdLockHolderFiber.joinWithNever
      } yield result

      (
        for {
          ctrl <- TestControl.execute(program)
          _ <- ctrl.tickFor(7.seconds)
          // by this time the second lock should still be holding, third lock should be waiting for its turn
          result1 <- ctrl.results
          _ <- ctrl.tickFor(2.seconds)
          // the second lock already be released, third lock should already return the value
          result2 <- ctrl.results
        } yield {
          result1 should not be defined
          result2 shouldEqual Outcome.Succeeded("third lock acquired").some
        }
      ).unsafeRunSync()(cats.effect.unsafe.implicits.global)
    }

    "return the function result if the lock has expired before the function completes" in runTestControl {
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(store)
        result <- sync(Id, expiry = 1.second)(IO.sleep(2.seconds).as("result")).attempt
        lockDataAfterRelease <- store.get(Id)
      } yield {
        // there is no lock row to delete anymore, which is not an error
        result shouldEqual "result".asRight
        lockDataAfterRelease shouldEqual None
      }
    }

    "fail with LockAcquireTimeoutError if the lock is not acquired within the timeout" in runTestControl {
      val timeout = 1.second
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(store)
        otherHolderLock <- acquireByOtherHolder(store, expiry = 1.hour)
        fStarted <- Ref.of[IO, Boolean](false)
        syncCallElapsedTimeWithResult <- sync(Id, timeout = timeout)(fStarted.set(true)).attempt.timed
        fStartedResult <- fStarted.get
        lockDataAfterAttempt <- store.get(Id)
      } yield {
        val (syncCallElapsedTime, syncCallResult) = syncCallElapsedTimeWithResult
        syncCallResult shouldEqual LockAcquireTimeoutError(timeout).asLeft
        syncCallElapsedTime should be >= timeout
        syncCallElapsedTime should be <= timeout + PollInterval
        fStartedResult shouldBe false
        // the lock of the current holder must not be removed
        lockDataAfterAttempt shouldEqual otherHolderLock.some
      }
    }

    "run the function if the lock insert in flight at the timeout acquires the lock" in runTestControl {
      val timeout = 1.second
      val insertDuration = 2.seconds
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(slowInsertLockStore(store, insertDuration))
        syncCallElapsedTimeWithResult <- sync(Id, timeout = timeout)(store.get(Id)).attempt.timed
        lockDataAfterRelease <- store.get(Id)
      } yield {
        val (syncCallElapsedTime, syncCallResult) = syncCallElapsedTimeWithResult
        // the lock is taken while running the function
        syncCallResult.map(_.isDefined) shouldEqual true.asRight
        syncCallElapsedTime shouldEqual insertDuration
        lockDataAfterRelease shouldEqual None
      }
    }

    "fail with LockAcquireTimeoutError after the lock insert in flight at the timeout completes" in runTestControl {
      val timeout = 1.second
      val insertDuration = 2.seconds
      for {
        store <- InMemoryLockStore.make
        sync = cassandraSync(slowInsertLockStore(store, insertDuration))
        otherHolderLock <- acquireByOtherHolder(store, expiry = 1.hour)
        fStarted <- Ref.of[IO, Boolean](false)
        syncCallElapsedTimeWithResult <- sync(Id, timeout = timeout)(fStarted.set(true)).attempt.timed
        fStartedResult <- fStarted.get
        lockDataAfterAttempt <- store.get(Id)
      } yield {
        val (syncCallElapsedTime, syncCallResult) = syncCallElapsedTimeWithResult
        syncCallResult shouldEqual LockAcquireTimeoutError(timeout).asLeft
        // the error is raised only once the insert completes, not when the timeout passes
        syncCallElapsedTime shouldEqual insertDuration
        fStartedResult shouldBe false
        // the lock of the current holder must not be removed
        lockDataAfterAttempt shouldEqual otherHolderLock.some
      }
    }

    "fail without running the function if the lock store fails" in runTestControl {
      val store = new LockStore[IO] {
        override def insertAcquire(lock: Lock): IO[Option[AcquiredLock]] = IO.raiseError(TestError)

        override def deleteIfAcquired(acquiredLock: AcquiredLock): IO[Boolean] = IO.pure(false)
      }
      for {
        fStarted <- Ref.of[IO, Boolean](false)
        result <- cassandraSync(store)(Id)(fStarted.set(true)).attempt
        fStartedResult <- fStarted.get
      } yield {
        result shouldEqual TestError.asLeft
        fStartedResult shouldBe false
      }
    }

    // TODO: https://github.com/evolution-gaming/cassandra-sync/issues/277
    // fix a lock release error replacing the result of successful `f`,
    // while it is ignored if `f` fails
    "return the function result if releasing the lock fails" ignore runTestControl {
      for {
        result <- cassandraSync(ReleaseFailingLockStore)(Id)(IO.pure("result")).attempt
      } yield {
        // the lock row expires by TTL anyway, while failing the call might make the caller rerun `f`
        result shouldEqual "result".asRight
      }
    }

    "fail with the function error if releasing the lock fails too" in runTestControl {
      for {
        result <- cassandraSync(ReleaseFailingLockStore)(Id)(IO.raiseError[Unit](TestError)).attempt
      } yield {
        result shouldEqual TestError.asLeft
      }
    }
  }
}

object CassandraSyncImplSpec {

  private val Id = "id"

  private val PollInterval = 100.millis

  private case object TestError extends RuntimeException("test error") with NoStackTrace

  private case object ReleaseError extends RuntimeException("release error") with NoStackTrace

  /**
   * Lock store which always acquires the lock but fails to release it.
   */
  private val ReleaseFailingLockStore: LockStore[IO] = new LockStore[IO] {
    override def insertAcquire(lock: Lock): IO[Option[AcquiredLock]] = IO.pure(AcquiredLock.fromLock(lock).some)

    override def deleteIfAcquired(acquiredLock: AcquiredLock): IO[Boolean] = IO.raiseError(ReleaseError)
  }

  /**
   * Lock store which, like a real query, applies the lock insert regardless of the caller being
   * canceled, while the response arrives after `insertDuration`.
   */
  private def slowInsertLockStore(store: LockStore[IO], insertDuration: FiniteDuration): LockStore[IO] = {
    new LockStore[IO] {
      override def insertAcquire(lock: Lock): IO[Option[AcquiredLock]] = {
        store.insertAcquire(lock) <* IO.sleep(insertDuration)
      }

      override def deleteIfAcquired(acquiredLock: AcquiredLock): IO[Boolean] = store.deleteIfAcquired(acquiredLock)
    }
  }

  /**
   * Acquires the lock with [[Id]] directly in the store, as another lock holder would do.
   */
  private def acquireByOtherHolder(store: LockStore[IO], expiry: FiniteDuration): IO[Lock] = {
    for {
      timestamp <- IO.realTimeInstant
      acquisitionId <- IO { UUID.randomUUID() }
      lock = Lock(id = Id, acquisitionId = acquisitionId, expiry = expiry, timestamp = timestamp, metadata = None)
      acquired <- store.insertAcquire(lock)
      _ <- IO.raiseWhen(acquired.isEmpty)(new IllegalStateException(s"lock $Id is already held"))
    } yield lock
  }

  private def cassandraSync(lockStore: LockStore[IO]): CassandraSync[IO] = {
    new CassandraSyncImpl[IO](lockStore, pollInterval = PollInterval)
  }

  /**
   * Runs the program with virtual time, so sleeps, lock expiration and timeouts don't slow down the
   * tests.
   */
  private def runTestControl[A](program: IO[A]): A = TestControl.executeEmbed(program)
    .unsafeRunSync()(cats.effect.unsafe.implicits.global)
}
