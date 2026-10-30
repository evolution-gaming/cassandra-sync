package com.evolution.cassandra.sync

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

final case class LockAcquireTimeoutError(timeout: FiniteDuration)
extends TimeoutException(s"Failed to acquire lock within $timeout")
// disabling stacktraces in order not to pay perf penalty on each call when used in ApplicativeError.raiseError
// which evaluates the argument eagerly
with NoStackTrace
