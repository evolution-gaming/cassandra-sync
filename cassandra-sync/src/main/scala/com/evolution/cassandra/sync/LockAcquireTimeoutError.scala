package com.evolution.cassandra.sync

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

case class LockAcquireTimeoutError(timeout: FiniteDuration)
extends TimeoutException(s"Failed to acquire lock within $timeout")
