package com.evolutiongaming.cassandra.sync

import java.util.UUID
import java.util.concurrent.Executors

import com.evolutiongaming.cassandra.StartCassandra
import com.evolutiongaming.concurrent.CurrentThreadExecutionContext
import com.evolutiongaming.scassandra.{CassandraConfig, CreateCluster}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class CassandraSyncSpec extends WordSpec with BeforeAndAfterAll with Matchers {
  import CassandraSyncSpec._

  private lazy val shutdownCassandra = StartCassandra()

  private val cluster = CreateCluster(CassandraConfig.Default)(CurrentThreadExecutionContext)

  override def beforeAll() = {
    super.beforeAll()
    shutdownCassandra
  }

  override def afterAll() = {
    cluster.close().await()
    shutdownCassandra()
    super.afterAll()
  }

  "CassandraSync" should {

    lazy val cassandraSync: CassandraSync = {
      implicit val session = cluster.connect().await()
      implicit val es = Executors.newScheduledThreadPool(3)
      CassandraSync(keyspace = "test", autoCreate = AutoCreate.KeyspaceAndTable.Default)
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
      the[LockAcquireTimeoutException] thrownBy result1.await(1.second)
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
