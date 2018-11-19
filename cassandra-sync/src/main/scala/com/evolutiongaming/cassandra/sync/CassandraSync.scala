package com.evolutiongaming.cassandra.sync

import java.time.Instant
import java.util.concurrent.ScheduledExecutorService

import com.evolutiongaming.concurrent.FutureHelper._
import com.evolutiongaming.scassandra.syntax._
import com.evolutiongaming.scassandra._

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.util.{Failure, Success, Try}

trait CassandraSync {
  /**
    * @param id       lock id
    * @param expiry   when to expiry the lock in case it was not removed gracefully
    * @param timeout  time given for acquiring the lock before timeout exception is thrown
    * @param metadata you can provide additional information for tracing origin of lock
    */
  def apply[A](
    id: CassandraSync.Id,
    expiry: FiniteDuration = 10.seconds,
    timeout: FiniteDuration = 30.seconds,
    metadata: Option[String] = None)(
    f: => Future[A]): Future[A]
}

object CassandraSync {
  type Id = String

  implicit val FiniteDurationEncode: EncodeByName[FiniteDuration] = EncodeByName[Long].imap(_.toMillis)
  implicit val FiniteDurationDecode: DecodeByName[FiniteDuration] = DecodeByName[Long].map(_.millis)

  def apply(
    keyspace: String,
    table: String = "locks",
    autoCreate: AutoCreate = AutoCreate.None,
    interval: FiniteDuration = 100.millis)(implicit es: ScheduledExecutorService, session: Session): CassandraSync = {

    implicit val ec = ExecutionContext.fromExecutor(es)

    val keyspaceTable = s"$keyspace.$table"

    def createTable() = {
      session.execute(
        s"CREATE TABLE IF NOT EXISTS $keyspaceTable (" +
          "id text PRIMARY KEY, " +
          "expiry_ms BIGINT, " +
          "timestamp TIMESTAMP," +
          "metadata TEXT)")
    }

    def createKeyspace(replicationStrategy: ReplicationStrategyConfig) = {
      val query = CreateKeyspaceIfNotExists(keyspace, replicationStrategy)
      session.execute(query)
    }

    def after[A](delay: FiniteDuration)(f: => A): Future[A] = {
      val promise = Promise[A]()
      val runnable = new Runnable {
        def run() = promise.success(f)
      }
      es.schedule(runnable, interval.length, interval.unit)
      promise.future
    }

    val created = autoCreate match {
      case AutoCreate.None                => Future.unit
      case AutoCreate.Table               => createTable()
      case a: AutoCreate.KeyspaceAndTable => for {
        _ <- createKeyspace(a.replicationStrategy)
        _ <- createTable()
      } yield {}
    }

    case class Statements(insert: Insert.Type, delete: Delete.Type)

    val statements = for {
      _ <- created
      insert = Insert(keyspaceTable)
      delete = Delete(keyspaceTable)
      insert <- insert
      delete <- delete
    } yield Statements(
      insert = insert,
      delete = delete)

    new CassandraSync {

      def apply[A](
        id: String,
        expiry: FiniteDuration,
        timeout: FiniteDuration,
        metadata: Option[String])(f: => Future[A]): Future[A] = {

        val timestamp = Instant.now()

        def insert(statements: Statements) = {

          def insert(deadline: Long): Future[Unit] = {
            for {
              applied <- statements.insert(id, expiry, timestamp, metadata)
              _ <- {
                if (applied) Future.unit
                else if (Platform.currentTime > deadline) Future.failed(LockAcquireTimeoutException(timeout))
                else after(interval)(insert(deadline)).flatten
              }
            } yield {}
          }

          val deadline = timestamp.toEpochMilli + timeout.toMillis
          insert(deadline)
        }

        for {
          statements <- statements
          _ <- insert(statements)
          result <- f.map[Try[A]](Success(_)).recover { case a => Failure(a) }
          _ <- statements.delete(id).recover { case _ => () }
        } yield {
          result.get
        }
      }
    }
  }


  object Insert {
    type Type = (Id, FiniteDuration, Instant, Option[String]) => Future[Boolean]

    def apply(table: String)(implicit ec: ExecutionContext, session: Session): Future[Type] = {
      val query = s"INSERT INTO $table (id, expiry_ms, timestamp, metadata) VALUES (?, ?, ?, ?) IF NOT EXISTS USING TTL ?"
      for {
        statement <- session.prepare(query)
      } yield {
        (id: Id, expiry: FiniteDuration, timestamp: Instant, metadata: Option[String]) => {
          val ttl = (expiry.toSeconds max 1l).toInt
          val bound = statement
            .bind()
            .encode("id", id)
            .encode("expiry_ms", expiry)
            .encode("timestamp", timestamp)
            .encode("metadata", metadata)
            .encode("[ttl]", ttl)
          for {
            result <- session.execute(bound)
          } yield {
            val row = result.one()
            row.decode[Boolean]("[applied]")
          }
        }
      }
    }
  }


  object Delete {
    type Type = Id => Future[Unit]

    def apply(table: String)(implicit ec: ExecutionContext, session: Session): Future[Type] = {
      for {
        statement <- session.prepare(s"DELETE FROM $table WHERE id = ?")
      } yield {
        id: Id => {
          val bound = statement
            .bind()
            .encode("id", id)
          session.execute(bound).unit
        }
      }
    }
  }
}

case class LockAcquireTimeoutException(timeout: FiniteDuration)
  extends TimeoutException(s"Failed to acquire lock within $timeout")