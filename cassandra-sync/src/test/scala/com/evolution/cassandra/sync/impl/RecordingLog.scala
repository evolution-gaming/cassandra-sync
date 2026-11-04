package com.evolution.cassandra.sync.impl

import cats.effect.*
import com.evolutiongaming.catshelper.Log

/**
 * [[Log]] for unit tests which keeps the written records in memory.
 *
 * MDC is ignored.
 */
private[sync] final class RecordingLog private (
  recordsRef: Ref[IO, Vector[RecordingLog.Record]],
) extends Log[IO] {
  import RecordingLog.*

  /**
   * Returns the written records in the order they were written.
   */
  def records: IO[Vector[Record]] = recordsRef.get

  override def trace(msg: => String, mdc: Log.Mdc): IO[Unit] = record(Level.Trace, msg, cause = None)

  override def debug(msg: => String, mdc: Log.Mdc): IO[Unit] = record(Level.Debug, msg, cause = None)

  override def info(msg: => String, mdc: Log.Mdc): IO[Unit] = record(Level.Info, msg, cause = None)

  override def warn(msg: => String, mdc: Log.Mdc): IO[Unit] = record(Level.Warn, msg, cause = None)

  override def warn(msg: => String, cause: Throwable, mdc: Log.Mdc): IO[Unit] = record(Level.Warn, msg, Some(cause))

  override def error(msg: => String, mdc: Log.Mdc): IO[Unit] = record(Level.Error, msg, cause = None)

  override def error(msg: => String, cause: Throwable, mdc: Log.Mdc): IO[Unit] = record(Level.Error, msg, Some(cause))

  private def record(level: Level, msg: String, cause: Option[Throwable]): IO[Unit] = {
    recordsRef.update(_ :+ Record(level, msg, cause))
  }
}

private[sync] object RecordingLog {

  sealed trait Level
  object Level {
    case object Trace extends Level
    case object Debug extends Level
    case object Info extends Level
    case object Warn extends Level
    case object Error extends Level
  }

  final case class Record(level: Level, msg: String, cause: Option[Throwable])

  def make: IO[RecordingLog] = Ref.of[IO, Vector[Record]](Vector.empty).map(new RecordingLog(_))
}
