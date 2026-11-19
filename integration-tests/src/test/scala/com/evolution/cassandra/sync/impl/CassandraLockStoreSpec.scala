package com.evolution.cassandra.sync.impl

import cats.effect.*
import cats.implicits.*
import com.datastax.oss.driver.api.core.cql.{Row, SimpleStatement}
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException
import com.evolution.cassandra.sync.{BaseCassandraSpec, LockStatements4x, SharedTestCassandra}
import com.evolution.scassandra.syntax.*
import org.scalatest.DoNotDiscover

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.concurrent.duration.*

/**
 * Verifies [[LockStore]] implementation with Cassandra.
 */
@DoNotDiscover // will be combined in Suites to share test resources
class CassandraLockStoreSpec(testCassandra: SharedTestCassandra) extends BaseCassandraSpec(testCassandra) {
  import CassandraLockStoreSpec.*

  // the locks table is created before all the tests
  private lazy val keyspace = ensureTestKeyspace("current")

  private lazy val store: LockStore[IO] = lockStore(keyspace).run()

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    session.execute(lockStatements(keyspace, Table).CreateTableIfNotExists).void.run()
  }

  "LockStore" should {

    "acquire the lock if it is not held" in new Scope {
      val lock = newLock(id)
      store.insertAcquire(lock).run() shouldEqual AcquiredLock.fromLock(lock).some
    }

    "acquire the lock again with the same acquisition, as when the driver retries an applied insert" in new Scope {
      val lock = newLock(id)
      store.insertAcquire(lock).run() shouldEqual AcquiredLock.fromLock(lock).some
      // the insert is not applied, but the conflicting lock row belongs to the same acquisition
      store.insertAcquire(lock).run() shouldEqual AcquiredLock.fromLock(lock).some
    }

    "not acquire the lock held by another acquisition" in new Scope {
      val lock = newLock(id)
      store.insertAcquire(lock).run() shouldBe defined
      store.insertAcquire(newLock(id)).run() shouldEqual None
      store.insertAcquire(newLock(id, metadata = lock.metadata)).run() shouldEqual None
    }

    "release the lock only by the acquisition holding it" in new Scope {
      val lock = newLock(id)
      val otherLock = newLock(id)
      val acquiredLock = store.insertAcquire(lock).run().value

      store.deleteIfAcquired(AcquiredLock.fromLock(otherLock)).run() shouldEqual false
      selectLockRow(keyspace, id) shouldBe defined

      store.deleteIfAcquired(acquiredLock).run() shouldEqual true
      selectLockRow(keyspace, id) shouldEqual None
      // releasing the lock again does nothing
      store.deleteIfAcquired(acquiredLock).run() shouldEqual false

      store.insertAcquire(otherLock).run() shouldEqual AcquiredLock.fromLock(otherLock).some
    }

    // the lock rows without the acquisition ID are also written by cassandra-sync 5.x instances
    // working in the fallback mode for the tables created by 4.x
    "not acquire or release the lock held by cassandra-sync 4.x" in new Scope {
      val lock = newLock(id, metadata = "metadata".some)
      insertIfNotExists4x(keyspace, id, metadata = "metadata".some) shouldEqual true

      store.insertAcquire(lock).run() shouldEqual None
      store.deleteIfAcquired(AcquiredLock.fromLock(lock)).run() shouldEqual false
      selectLockRow(keyspace, id) shouldBe defined

      delete4x(keyspace, id) shouldEqual true
      store.insertAcquire(lock).run() shouldEqual AcquiredLock.fromLock(lock).some
      // and cassandra-sync 4.x can't acquire the lock held by the current version
      insertIfNotExists4x(keyspace, id, metadata = "metadata".some) shouldEqual false
    }

    "store the lock data" in new Scope {
      val lock = newLock(id, metadata = "metadata".some)
      store.insertAcquire(lock).run() shouldBe defined

      val row = selectLockRow(keyspace, id).value
      row.decode[Option[UUID]]("acquisition_id") shouldEqual lock.acquisitionId.some
      row.decode[Option[String]]("metadata") shouldEqual "metadata".some
      row.decode[Long]("expiry_ms") shouldEqual lock.expiry.toMillis
      row.decode[Instant]("timestamp") shouldEqual lock.timestamp
      selectLockRowTtl(keyspace, id).value should (be > 0 and be <= LockStore.ttlSeconds(lock.expiry))
    }

    "store the lock data if there is no metadata" in new Scope {
      val lock = newLock(id, metadata = None)
      store.insertAcquire(lock).run() shouldBe defined

      val row = selectLockRow(keyspace, id).value
      row.decode[Option[UUID]]("acquisition_id") shouldEqual lock.acquisitionId.some
      row.decode[Option[String]]("metadata") shouldEqual None
    }

    "fail to be created if the table does not exist" in {
      an[InvalidQueryException] should be thrownBy lockStore(keyspace, table = "missing_table").run()
    }

    "fail to be created if the keyspace does not exist" in {
      an[InvalidQueryException] should be thrownBy lockStore("missing").run()
    }

    "fail to be created if the table has the 4.x schema without the acquisition ID column" in {
      val table = "locks_4x"
      session.execute(LockStatements4x.createTableIfNotExists(keyspace, table)).run()
      an[InvalidQueryException] should be thrownBy lockStore(keyspace, table = table).run()
    }
  }

  private def lockStore(keyspace: String, table: String = Table): IO[LockStore[IO]] = {
    LockStore.of(session, lockStatements(keyspace, table))
  }

  private def selectLockRow(keyspace: String, id: String): Option[Row] = {
    session
      .execute(SimpleStatement.newInstance(s"SELECT * FROM $keyspace.$Table WHERE id = ?", id))
      .map(resultSet => Option(resultSet.one()))
      .run()
  }

  private def selectLockRowTtl(keyspace: String, id: String): Option[Int] = {
    session
      .execute(SimpleStatement.newInstance(s"SELECT TTL(expiry_ms) AS ttl FROM $keyspace.$Table WHERE id = ?", id))
      .map(resultSet => Option(resultSet.one()).flatMap(_.decode[Option[Int]]("ttl")))
      .run()
  }

  /**
   * Inserts a lock row the same way cassandra-sync 4.x does, returns true if it was inserted.
   */
  private def insertIfNotExists4x(keyspace: String, id: String, metadata: Option[String]): Boolean = {
    for {
      statement <- session.prepare(LockStatements4x.insertIfNotExists(keyspace, Table))
      timestamp <- IO.realTimeInstant
      resultSet <- session.execute(
        statement
          .bind()
          .encode("id", id)
          .encode("expiry_ms", LockExpiry.toMillis)
          .encode("timestamp", timestamp)
          .encode("metadata", metadata)
          .encode("[ttl]", LockStore.ttlSeconds(LockExpiry)),
      )
    } yield resultSet.wasApplied()
  }.run()

  /**
   * Deletes a lock row the same way cassandra-sync 4.x does, returns true if it was deleted.
   */
  private def delete4x(keyspace: String, id: String): Boolean = {
    for {
      statement <- session.prepare(LockStatements4x.delete(keyspace, Table))
      resultSet <- session.execute(statement.bind().encode("id", id))
    } yield resultSet.wasApplied()
  }.run()

  private trait Scope {
    val id: String = UUID.randomUUID().toString
  }
}

object CassandraLockStoreSpec {

  private val Table = "locks"

  private val LockExpiry = 1.minute

  private def lockStatements(keyspace: String, table: String): LockStatements = {
    new LockStatements(keyspace = keyspace, table = table)
  }

  /**
   * Creates a lock with a new acquisition ID.
   *
   * The timestamp is truncated to milliseconds, as it is stored by Cassandra.
   */
  private def newLock(id: String, metadata: Option[String] = "metadata".some): Lock = Lock(
    id = id,
    acquisitionId = UUID.randomUUID(),
    expiry = LockExpiry,
    timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS),
    metadata = metadata,
  )
}
