package com.evolutiongaming.cassandra.sync

import java.time.Instant

import cats.effect.{Clock, Resource, Sync, Timer}
import cats.implicits._
import cats.{FlatMap, ~>}
import com.evolutiongaming.catshelper.ClockHelper._
import com.evolutiongaming.scassandra._
import com.evolutiongaming.scassandra.syntax._

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

trait CassandraSync[F[_]] {
  /**
    * @param id       lock id
    * @param expiry   when to expiry the lock in case it was not removed gracefully
    * @param timeout  time given for acquiring the lock before timeout exception is thrown
    * @param metadata you can provide additional information for tracing origin of lock
    */
  def apply[A](
    id: CassandraSync.Id,
    expiry: FiniteDuration = 30.seconds,
    timeout: FiniteDuration = 30.seconds,
    metadata: Option[String] = None)(
    f: => F[A]
  ): F[A]
}

object CassandraSync {

  type Id = String

  implicit val FiniteDurationEncode: EncodeByName[FiniteDuration] = EncodeByName[Long].imap(_.toMillis)

  implicit val FiniteDurationDecode: DecodeByName[FiniteDuration] = DecodeByName[Long].map(_.millis)


  def of[F[_] : Sync : Timer](
    session: CassandraSession[F],
    keyspace: String,
    table: String = "locks",
    autoCreate: AutoCreate = AutoCreate.None,
    interval: FiniteDuration = 100.millis,
  ): F[CassandraSync[F]] = {

    val keyspaceTable = s"$keyspace.$table"

    def createTable = {
      session.execute(
        s"CREATE TABLE IF NOT EXISTS $keyspaceTable (" +
          "id text PRIMARY KEY, " +
          "expiry_ms BIGINT, " +
          "timestamp TIMESTAMP," +
          "metadata TEXT)").void
    }

    def createKeyspace(replicationStrategy: ReplicationStrategyConfig) = {
      val query = CreateKeyspaceIfNotExists(keyspace, replicationStrategy)
      session.execute(query)
    }

    def createTableAndKeyspace(replicationStrategy: ReplicationStrategyConfig) = for {
      _ <- createKeyspace(replicationStrategy)
      _ <- createTable
    } yield {}

    val created = autoCreate match {
      case AutoCreate.None                => ().pure[F]
      case AutoCreate.Table               => createTable
      case a: AutoCreate.KeyspaceAndTable => createTableAndKeyspace(a.replicationStrategy)
    }

    for {
      _      <- created
      insert <- Insert.of(keyspaceTable, session)
      delete <- Delete.of(keyspaceTable, session)
    } yield {
      val statements = Statements(insert = insert, delete = delete)
      apply(interval, statements)
    }
  }


  def apply[F[_] : Sync : Timer](
    interval: FiniteDuration,
    statements: Statements[F]
  ): CassandraSync[F] = {

    new CassandraSync[F] {

      def apply[A](
        id: String,
        expiry: FiniteDuration,
        timeout: FiniteDuration,
        metadata: Option[String])(
        f: => F[A]
      ): F[A] = {

        def lock(timestamp: Instant) = {

          val deadline = timestamp.toEpochMilli + timeout.toMillis

          def timeoutError = LockAcquireTimeoutError(timeout)

          val checkDeadline = for {
            now <- Clock[F].instant
            _   <- if (now.toEpochMilli > deadline) timeoutError.raiseError[F, Instant] else now.pure[F]
          } yield now

          def retry = for {
            _         <- checkDeadline
            _         <- Timer[F].sleep(interval)
            timestamp <- checkDeadline
          } yield timestamp

          val lock = timestamp.tailRecM { timestamp =>
            for {
              applied <- statements.insert(id, expiry, timestamp, metadata)
              result <- if (applied) ().asRight[Instant].pure[F] else retry.map(_.asLeft[Unit])
              _      <- Timer[F].sleep(interval)
            } yield result
          }

          val unlock = statements.delete(id)

          val result = for {
            a <- lock
          } yield {
            (a, unlock)
          }
          Resource(result)
        }

        for {
          timestamp <- Clock[F].instant
          result    <- lock(timestamp).use { _ => f }
        } yield result
      }
    }
  }


  trait Insert[F[_]] {

    def apply(id: Id, expiry: FiniteDuration, timestamp: Instant, metadata: Option[String]): F[Boolean]
  }

  object Insert {

    def of[F[_] : FlatMap](table: String, session: CassandraSession[F]): F[Insert[F]] = {
      val query = s"INSERT INTO $table (id, expiry_ms, timestamp, metadata) VALUES (?, ?, ?, ?) IF NOT EXISTS USING TTL ?"
      for {
        statement <- session.prepare(query)
      } yield {
        new Insert[F] {
          def apply(id: Id, expiry: FiniteDuration, timestamp: Instant, metadata: Option[String]) = {
            val ttl = (expiry.toSeconds max 1l).toInt
            val bound = statement
              .bind()
              .encode("id", id)
              .encode("expiry_ms", expiry)
              .encode("timestamp", timestamp)
              .encode("metadata", metadata)
              .encode("[ttl]", ttl)
              .setIdempotent(true)
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
  }


  trait Delete[F[_]] {

    def apply(id: Id): F[Unit]
  }

  object Delete {

    def of[F[_] : FlatMap](table: String, session: CassandraSession[F]): F[Delete[F]] = {
      for {
        statement <- session.prepare(s"DELETE FROM $table WHERE id = ?")
      } yield {
        new Delete[F] {
          def apply(id: Id) = {
            val bound = statement
              .bind()
              .encode("id", id)
              .setIdempotent(true)
            session.execute(bound).void
          }
        }
      }
    }
  }


  final case class Statements[F[_]](insert: Insert[F], delete: Delete[F])


  implicit class CassandraSyncOps[F[_]](val self: CassandraSync[F]) extends AnyVal {

    def mapK[G[_]](f: F ~> G, g: G ~> F): CassandraSync[G] = new CassandraSync[G] {

      def apply[A](
        id: Id,
        expiry: FiniteDuration,
        timeout: FiniteDuration,
        metadata: Option[String])(
        f1: => G[A]
      ) = {

        f(self(id, expiry, timeout, metadata)(g(f1)))
      }
    }
  }
}

case class LockAcquireTimeoutError(timeout: FiniteDuration)
  extends TimeoutException(s"Failed to acquire lock within $timeout")