package com.evolution.cassandra.sync

import cats.effect.*
import org.testcontainers.cassandra.CassandraContainer

object TestCassandra {
  val DockerImage: String = "cassandra:4.1.12"

  def run: ResourceIO[CassandraContainer] = {
    val idleResource = Resource.make(IO {
      new CassandraContainer(DockerImage)
    }) { container =>
      IO.blocking {
        container.stop()
      }
    }

    idleResource.evalMap(container => IO.blocking { container.start(); container })
  }
}
