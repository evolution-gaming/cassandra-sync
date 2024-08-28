package com.evolutiongaming.cassandra.sync

import java.util.UUID
import cats.arrow.FunctionK
import cats.effect.unsafe.implicits
import cats.effect.{IO, Resource}
import com.evolutiongaming.catshelper.CatsHelper._
import com.dimafeng.testcontainers.CassandraContainer
import org.testcontainers.utility.DockerImageName
import com.evolutiongaming.scassandra.{CassandraCluster, CassandraConfig}
import com.evolutiongaming.cassandra.sync.IOSuite._
import com.evolutiongaming.catshelper.FromFuture
import com.evolutiongaming.nel.Nel
import org.scalatest.BeforeAndAfterAll

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CassandraSyncSpec extends AnyWordSpec with BeforeAndAfterAll with Matchers {
  import CassandraSyncSpec._
  private lazy val cassandraContainer = CassandraContainer(
    dockerImageNameOverride = DockerImageName.parse("cassandra:3.11.7"),
  )

   private lazy val config =
    CassandraConfig.Default.copy(
      contactPoints = Nel(cassandraContainer.containerIpAddress),
      port = cassandraContainer.mappedPort(9042),
    )
 // due to test structure we need to start the container before the test suite
  cassandraContainer.start()

  implicit val ioRuntime = implicits.global


  private lazy val (cluster, clusterRelease) = {
    val cluster = CassandraCluster.of[IO](CassandraConfig.Default, clusterId = 0)
    cluster.allocated.toTry.get
  }


  override def afterAll() = {
    clusterRelease.toTry.get
    cassandraContainer.stop()
    super.afterAll()
  }

  "CassandraSync" should {

    lazy val (cassandraSync, _) = {
      val cassandraSync = for {
        session       <- cluster.connect
        cassandraSync  = CassandraSync.of(session, keyspace = "test", autoCreate = AutoCreate.KeyspaceAndTable.Default)
        cassandraSync <- Resource.eval(cassandraSync)
      } yield {
        val toFuture = new FunctionK[IO, Future] {
          def apply[A](fa: IO[A]) = fa.toFuture
        }
        val fromFuture = new FunctionK[Future, IO] {
          def apply[A](fa: Future[A]) = FromFuture[IO].apply { fa }
        }
        cassandraSync.mapK(toFuture, fromFuture)
      }
      cassandraSync.allocated.toTry.get
    }

    "execute functions one by one" in new Scope {
      val promise = Promise[Int]()
      val expiry = 1.minute
      val result0 = cassandraSync(id, expiry, expiry)(promise.future)
      the[TimeoutException] thrownBy result0.await(1.second)
      val result1 = cassandraSync(id, expiry, expiry)(Future.successful(1))
      the[TimeoutException] thrownBy result1.await(1.second)
      promise.success(0)
      result0.await() shouldEqual 0
      result1.await() shouldEqual 1
    }

    "unlock when expired" in new Scope {
      val expiry = 1.second
      val timeout = 1.minute
      val result0 = cassandraSync(id, expiry, timeout)(Promise[Unit]().future)
      the[TimeoutException] thrownBy result0.await(1.second)
      val result1 = cassandraSync(id, expiry, timeout)(Future.successful(1))
      result1.await() shouldEqual 1
    }

    "unlock if failed" in new Scope {
      val promise = Promise[Int]()
      val expiry = 1.minute
      val result0 = cassandraSync(id, expiry, expiry)(promise.future)
      the[TimeoutException] thrownBy result0.await(1.second)
      val result1 = cassandraSync(id, expiry, expiry)(Future.successful(1))
      the[TimeoutException] thrownBy result1.await(1.second)
      val failure = new RuntimeException with NoStackTrace
      promise.failure(failure)
      the[RuntimeException] thrownBy result0.await()
      result1.await() shouldEqual 1
    }

    "fail if timed out" in new Scope {
      val expiry = 1.minute
      val timeout = 300.millis
      val result0 = cassandraSync(id, expiry, timeout)(Promise[Unit]().future)
      the[TimeoutException] thrownBy result0.await(1.second)
      val result1 = cassandraSync(id, expiry, timeout)(Future.successful(1))
      the[LockAcquireTimeoutError] thrownBy result1.await(1.second)
    }
  }

  private trait Scope {
    val id = UUID.randomUUID().toString
  }
}

object CassandraSyncSpec {

  implicit class FutureOps[A](val self: Future[A]) extends AnyVal {
    def await(timeout: FiniteDuration = 10.seconds): A = Await.result(self, timeout)
  }
}
