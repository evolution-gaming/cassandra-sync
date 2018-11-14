package com.evolutiongaming.cassandra.sync

import com.evolutiongaming.scassandra.ReplicationStrategyConfig

sealed trait AutoCreate

object AutoCreate {

  case object None extends AutoCreate

  case object Table extends AutoCreate
  

  final case class KeyspaceAndTable(replicationStrategy: ReplicationStrategyConfig) extends AutoCreate

  object KeyspaceAndTable {
    val Default: KeyspaceAndTable = KeyspaceAndTable(ReplicationStrategyConfig.Default)
  }
}