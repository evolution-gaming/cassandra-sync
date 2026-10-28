package com.evolution.cassandra.sync.impl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class LockStoreSpec extends AnyFreeSpec with Matchers {
  "ttlSeconds" - {

    // from FiniteDuration scaladoc: Duration is limited to +-(2^63-1)ns (ca. 292 years)
    val minDuration: FiniteDuration = -Long.MaxValue.nanos
    val maxDuration: FiniteDuration = Long.MaxValue.nanos

    "should return 1 for durations <= 1s" in {
      LockStore.ttlSeconds(minDuration) shouldEqual 1
      LockStore.ttlSeconds(-5.seconds) shouldEqual 1
      LockStore.ttlSeconds(Duration.Zero) shouldEqual 1
      LockStore.ttlSeconds(500.millis) shouldEqual 1
      LockStore.ttlSeconds(1.second) shouldEqual 1
    }

    "should round up for durations > 1s" in {
      LockStore.ttlSeconds(1.second + 1.nano) shouldEqual 2
      LockStore.ttlSeconds(2500.millis) shouldEqual 3
      LockStore.ttlSeconds(10.seconds) shouldEqual 10
      // check for potential Double conversion shenanigans: above 2^53 ns, a Double can't hold every nanosecond value
      LockStore.ttlSeconds(16777216.seconds + 1.nano) shouldEqual 16777217
    }

    "should not overflow but cap at Int.MaxValue for very long durations" in {
      LockStore.ttlSeconds(maxDuration) shouldEqual Int.MaxValue
    }
  }
}
