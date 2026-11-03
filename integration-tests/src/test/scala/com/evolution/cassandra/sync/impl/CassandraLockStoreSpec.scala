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
 * Verifies [[LockStore]] implementations with Cassandra, for both the current locks table schema
 * and the schema of cassandra-sync 4.x.
 */
@DoNotDiscover // will be combined in Suites to share test resources
class CassandraLockStoreSpec(testCassandra: SharedTestCassandra) extends BaseCassandraSpec(testCassandra) {
  import CassandraLockStoreSpec.*

  // the locks table with the current schema is created before all the tests
  private lazy val currentSchemaKeyspace = ensureTestKeyspace("current")
  // the locks table with the 4.x schema is created before all the tests
  private lazy val schema4xKeyspace = ensureTestKeyspace("4x")
  // the locks table is created and migrated by the test
  private lazy val migratedSchemaKeyspace = ensureTestKeyspace("migrated")

  private lazy val currentSchemaLockStore: LockStore[IO] = lockStore(currentSchemaKeyspace).run()

  private lazy val schema4xLockStore: LockStore[IO] = lockStore(schema4xKeyspace).run()

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val createTables = for {
      _ <- session.execute(lockStatements(currentSchemaKeyspace, Table).CreateTableIfNotExists)
      _ <- session.execute(LockStatements4x.createTableIfNotExists(schema4xKeyspace, Table))
    } yield ()
    createTables.run()
  }

  "LockStore with the current table schema" should {

    behave like lockStoreBehavior(currentSchemaKeyspace, currentSchemaLockStore)

    "store the acquisition ID and the metadata in separate columns" in new Scope {
      val lock = newLock(id, metadata = "metadata".some)
      currentSchemaLockStore.insertAcquire(lock).run() shouldBe defined

      val row = selectLockRow(currentSchemaKeyspace, id).value
      row.decode[Option[UUID]]("acquisition_id") shouldEqual lock.acquisitionId.some
      row.decode[Option[String]]("metadata") shouldEqual "metadata".some
      row.decode[Long]("expiry_ms") shouldEqual lock.expiry.toMillis
      row.decode[Instant]("timestamp") shouldEqual lock.timestamp
      selectLockRowTtl(currentSchemaKeyspace, id).value should (be > 0 and be <= LockStore.ttlSeconds(lock.expiry))
    }

    "store the acquisition ID if there is no metadata" in new Scope {
      val lock = newLock(id, metadata = None)
      currentSchemaLockStore.insertAcquire(lock).run() shouldBe defined

      val row = selectLockRow(currentSchemaKeyspace, id).value
      row.decode[Option[UUID]]("acquisition_id") shouldEqual lock.acquisitionId.some
      row.decode[Option[String]]("metadata") shouldEqual None
    }
  }

  "LockStore with the 4.x table schema" should {

    behave like lockStoreBehavior(schema4xKeyspace, schema4xLockStore)

    "store the acquisition ID along with the metadata in the metadata column" in new Scope {
      val lock = newLock(id, metadata = "metadata".some)
      schema4xLockStore.insertAcquire(lock).run() shouldBe defined

      val row = selectLockRow(schema4xKeyspace, id).value
      row.decode[Option[String]]("metadata") shouldEqual s"${ lock.acquisitionId }|metadata".some
      row.decode[Long]("expiry_ms") shouldEqual lock.expiry.toMillis
      row.decode[Instant]("timestamp") shouldEqual lock.timestamp
      selectLockRowTtl(schema4xKeyspace, id).value should (be > 0 and be <= LockStore.ttlSeconds(lock.expiry))
    }

    "store the acquisition ID in the metadata column if there is no metadata" in new Scope {
      val lock = newLock(id, metadata = None)
      schema4xLockStore.insertAcquire(lock).run() shouldBe defined

      val row = selectLockRow(schema4xKeyspace, id).value
      row.decode[Option[String]]("metadata") shouldEqual lock.acquisitionId.toString.some
    }
  }

  "LockStore" should {

    "fail to be created if the table does not exist" in {
      an[InvalidQueryException] should be thrownBy lockStore(currentSchemaKeyspace, table = "missing_table").run()
    }

    "fail to be created if the keyspace does not exist" in {
      an[InvalidQueryException] should be thrownBy lockStore("missing").run()
    }

    "use the current table schema if the names are given in a different case" in new Scope {
      val store = lockStore(currentSchemaKeyspace.toUpperCase, table = Table.toUpperCase).run()
      val lock = newLock(id)
      store.insertAcquire(lock).run() shouldBe defined
      selectLockRow(currentSchemaKeyspace, id).value.decode[Option[UUID]]("acquisition_id") shouldEqual
        lock.acquisitionId.some
    }

    "keep the lock exclusive between the lock stores created before and after the table schema migration" in new Scope {
      session.execute(LockStatements4x.createTableIfNotExists(migratedSchemaKeyspace, Table)).run()
      val storeBeforeMigration = lockStore(migratedSchemaKeyspace).run()
      session.execute(s"ALTER TABLE $migratedSchemaKeyspace.$Table ADD acquisition_id UUID").run()
      val storeAfterMigration = lockStore(migratedSchemaKeyspace).run()

      val lockBeforeMigration = newLock(id)
      val lockAfterMigration = newLock(id)

      // the lock held by the store created before the migration
      val acquiredBeforeMigration = storeBeforeMigration.insertAcquire(lockBeforeMigration).run().value
      storeAfterMigration.insertAcquire(lockAfterMigration).run() shouldEqual None
      storeAfterMigration.deleteIfAcquired(AcquiredLock.fromLock(lockAfterMigration)).run() shouldEqual false
      storeBeforeMigration.deleteIfAcquired(acquiredBeforeMigration).run() shouldEqual true

      // the lock held by the store created after the migration
      val acquiredAfterMigration = storeAfterMigration.insertAcquire(lockAfterMigration).run().value
      // the store created after the migration uses the acquisition ID column
      selectLockRow(migratedSchemaKeyspace, id).value.decode[Option[UUID]]("acquisition_id") shouldEqual
        lockAfterMigration.acquisitionId.some
      storeBeforeMigration.insertAcquire(lockBeforeMigration).run() shouldEqual None
      storeBeforeMigration.deleteIfAcquired(acquiredBeforeMigration).run() shouldEqual false
      storeAfterMigration.deleteIfAcquired(acquiredAfterMigration).run() shouldEqual true

      selectLockRow(migratedSchemaKeyspace, id) shouldEqual None
    }
  }

  private def lockStoreBehavior(keyspace: => String, store: => LockStore[IO]): Unit = {

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
  }

  private def lockStore(keyspace: String, table: String = Table): IO[LockStore[IO]] = {
    LockStore.of(session, lockStatements(keyspace, table), keyspace = keyspace, table = table)
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

  /**
   * Locks table name, the same in each keyspace of the spec.
   */
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
