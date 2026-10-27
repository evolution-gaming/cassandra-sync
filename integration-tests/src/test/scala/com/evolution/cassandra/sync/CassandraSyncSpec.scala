package com.evolution.cassandra.sync

import cats.arrow.FunctionK
import cats.effect.*
import com.datastax.oss.driver.api.core.CqlSessionBuilder
import com.evolution.scassandra.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import scala.concurrent.*
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

class CassandraSyncSpec extends AnyWordSpec with BeforeAndAfterAll with Matchers with IntegrationIOSuite {
  import CassandraSyncSpec.*

  // default testcontainers startup timeout is 60 seconds
  private lazy val (cassandraSync, resourcesRelease) = runResources.allocated.run(timeout = 65.seconds)
  private lazy val cassandraSyncFuture: CassandraSync[Future] = {
    val toFuture = new FunctionK[IO, Future] {
      def apply[A](fa: IO[A]): Future[A] = fa.unsafeToFuture()
    }
    val fromFuture = new FunctionK[Future, IO] {
      def apply[A](fa: Future[A]): IO[A] = IO.fromFuture(IO.pure(fa))
    }

    cassandraSync.mapK(toFuture, fromFuture)
  }

  override def afterAll(): Unit = {
    resourcesRelease.run()
    super.afterAll()
  }

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
  }

  private trait Scope {
    val id = UUID.randomUUID().toString
  }
}

object CassandraSyncSpec {

  private def runResources: ResourceIO[CassandraSync[IO]] = {
    for {
      cassandra <- TestCassandra.run
      sessionBuilder = new CqlSessionBuilder()
        .addContactPoint(cassandra.getContactPoint)
        .withLocalDatacenter(cassandra.getLocalDatacenter)
      session <- CassandraSession.make[IO](sessionBuilder)
      sync <- Resource.eval(CassandraSync.of(
        session,
        keyspace = "test",
        autoCreate = AutoCreate.KeyspaceAndTable.Default,
      ))
    } yield sync
  }
}
