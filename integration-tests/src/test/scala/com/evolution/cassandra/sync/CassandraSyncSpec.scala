package com.evolution.cassandra.sync

import cats.arrow.FunctionK
import cats.effect.*
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException
import org.scalatest.DoNotDiscover

import java.util.UUID
import scala.concurrent.*
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

@DoNotDiscover // will be combined in Suites to share test resources
class CassandraSyncSpec(testCassandra: SharedTestCassandra) extends BaseCassandraSpec(testCassandra) {

  // the locks table is created by CassandraSync
  private lazy val keyspace = ensureTestKeyspace("current")

  private lazy val cassandraSyncFuture: CassandraSync[Future] = toFuture(
    CassandraSync.of(
      session,
      keyspace = keyspace,
      autoCreate = AutoCreate.Table,
    ).run(),
  )

  "CassandraSync" should {

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

    "fail to be created if the table has the 4.x schema without the acquisition ID column" in {
      val table = "locks_4x"
      session.execute(LockStatements4x.createTableIfNotExists(keyspace, table)).run()
      // the existing table is not altered by AutoCreate
      an[InvalidQueryException] should be thrownBy
        CassandraSync.of(session, keyspace = keyspace, table = table, autoCreate = AutoCreate.Table).run()
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
