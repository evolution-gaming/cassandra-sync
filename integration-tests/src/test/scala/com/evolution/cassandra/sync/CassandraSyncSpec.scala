package com.evolution.cassandra.sync

import cats.arrow.FunctionK
import cats.effect.*
import org.scalatest.DoNotDiscover

import java.util.UUID
import scala.concurrent.*
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

@DoNotDiscover // will be combined in Suites to share test resources
class CassandraSyncSpec(testCassandra: SharedTestCassandra) extends BaseCassandraSpec(testCassandra) {

  // the locks table with the current schema is created by CassandraSync
  private lazy val currentSchemaKeyspace = ensureTestKeyspace("current")
  // the locks table with the 4.x schema is created before all the tests, as if it was created by cassandra-sync 4.x
  private lazy val schema4xKeyspace = ensureTestKeyspace("4x")

  private lazy val currentSchemaCassandraSyncFuture: CassandraSync[Future] = toFuture(
    CassandraSync.of(
      session,
      keyspace = currentSchemaKeyspace,
      autoCreate = AutoCreate.Table,
    ).run(),
  )
  private lazy val schema4xCassandraSyncFuture: CassandraSync[Future] = toFuture(
    CassandraSync.of(
      session,
      keyspace = schema4xKeyspace,
    ).run(),
  )

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    session.execute(LockStatements4x.createTableIfNotExists(schema4xKeyspace, "locks")).void.run()
  }

  "CassandraSync with the current table schema" should {
    behave like cassandraSync(currentSchemaCassandraSyncFuture)
  }

  "CassandraSync with the 4.x table schema" should {
    behave like cassandraSync(schema4xCassandraSyncFuture)
  }

  private def cassandraSync(cassandraSyncFuture: => CassandraSync[Future]): Unit = {

    "execute functions one by one" in new Scope {
      val promise = Promise[Int]()
      val expiry = 1.minute
      val result0 = cassandraSyncFuture(id, expiry, expiry)(promise.future)
      the[TimeoutException] thrownBy result0.await(1.second)
      val result1 = cassandraSyncFuture(id, expiry, expiry)(Future.successful(1))
      the[TimeoutException] thrownBy result1.await(1.second)
      promise.success(0)
      result0.await() shouldEqual 0
      result1.await() shouldEqual 1
    }

    "unlock when expired" in new Scope {
      val expiry = 1.second
      val timeout = 1.minute
      val result0 = cassandraSyncFuture(id, expiry, timeout)(Promise[Unit]().future)
      the[TimeoutException] thrownBy result0.await(1.second)
      val result1 = cassandraSyncFuture(id, expiry, timeout)(Future.successful(1))
      result1.await() shouldEqual 1
    }

    "unlock if failed" in new Scope {
      val promise = Promise[Int]()
      val expiry = 1.minute
      val result0 = cassandraSyncFuture(id, expiry, expiry)(promise.future)
      the[TimeoutException] thrownBy result0.await(1.second)
      val result1 = cassandraSyncFuture(id, expiry, expiry)(Future.successful(1))
      the[TimeoutException] thrownBy result1.await(1.second)
      val failure = new RuntimeException with NoStackTrace
      promise.failure(failure)
      the[RuntimeException] thrownBy result0.await()
      result1.await() shouldEqual 1
    }

    "fail if timed out" in new Scope {
      val expiry = 1.minute
      val timeout = 300.millis
      val result0 = cassandraSyncFuture(id, expiry, timeout)(Promise[Unit]().future)
      the[TimeoutException] thrownBy result0.await(1.second)
      val result1 = cassandraSyncFuture(id, expiry, timeout)(Future.successful(1))
      the[LockAcquireTimeoutError] thrownBy result1.await(1.second)
    }
  }

  private def toFuture(cassandraSync: CassandraSync[IO]): CassandraSync[Future] = {
    val toFuture = new FunctionK[IO, Future] {
      def apply[A](fa: IO[A]): Future[A] = fa.unsafeToFuture()
    }
    val fromFuture = new FunctionK[Future, IO] {
      def apply[A](fa: Future[A]): IO[A] = IO.fromFuture(IO { fa })
    }

    cassandraSync.mapK(toFuture, fromFuture)
  }

  private trait Scope {
    val id: String = UUID.randomUUID().toString
  }
}
