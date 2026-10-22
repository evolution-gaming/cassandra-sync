package com.evolution.cassandra.sync

import com.evolution.scassandra.ReplicationStrategyConfig

sealed trait AutoCreate

object AutoCreate {

  case object None extends AutoCreate

  case object Table extends AutoCreate

  final case class KeyspaceAndTable(replicationStrategy: ReplicationStrategyConfig) extends AutoCreate

  object KeyspaceAndTable {
    val Default: KeyspaceAndTable = KeyspaceAndTable(ReplicationStrategyConfig.Default)
  }
}
