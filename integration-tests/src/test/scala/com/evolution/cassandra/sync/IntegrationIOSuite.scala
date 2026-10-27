package com.evolution.cassandra.sync

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.*
import scala.concurrent.duration.*

trait IntegrationIOSuite {
  def defaultActionTimeout: FiniteDuration = 30.seconds

  implicit def ioRuntime: IORuntime = cats.effect.unsafe.implicits.global
  implicit def executionContext: ExecutionContextExecutor = ExecutionContext.global

  implicit class IOOps[A](val self: IO[A]) {
    def run(timeout: FiniteDuration = defaultActionTimeout): A = {
      self.timeout(timeout).unsafeRunSync()
    }
  }

  implicit class FutureOps[A](val self: Future[A]) {
    def await(timeout: FiniteDuration = defaultActionTimeout): A = Await.result(self, timeout)
  }
}
