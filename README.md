# cassandra-sync
[![Build Status](https://github.com/evolution-gaming/cassandra-sync/workflows/CI/badge.svg)](https://github.com/evolution-gaming/cassandra-sync/actions?query=workflow%3ACI)
[![Coverage Status](https://coveralls.io/repos/evolution-gaming/cassandra-sync/badge.svg)](https://coveralls.io/r/evolution-gaming/cassandra-sync)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/a804ed95dc044adb83f56411d7bacffc)](https://app.codacy.com/gh/evolution-gaming/cassandra-sync/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)
[![Version](https://img.shields.io/badge/version-click-blue)](https://evolution.jfrog.io/artifactory/api/search/latestVersion?g=com.evolutiongaming&a=cassandra-sync_2.13&repos=public)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellowgreen.svg)](https://opensource.org/licenses/MIT)

This tiny library provides mechanism of `synchronization` via locks stored in Cassandra table.
This `synchronization` is especially useful for preventing concurrent schema changes.
Sad that concurrent schema changes [is not supported](https://issues.apache.org/jira/browse/CASSANDRA-10699) by Cassandra on it's own.

## Concept 

Basically this is a mutex implementation via records stored in cassandra.
We insert record in order to acquire the lock and remove record when done.
We also provide expiry duration per lock in case we failed to remove record.
There is nice [statement](https://stackoverflow.com/a/34558/301517) which ideally describes the concept.

## Usage

```scala
def example(session: CassandraSession[IO]) = {
  for {
    cassandraSync <- CassandraSync.of(session, keyspace = "app")
    result        <- cassandraSync("id", expiry = 3.seconds, timeout = 10.seconds) {
      // put your code here 
      // the lock in cassandra per id will ensure that your code runs strictly sequentially
    }
  } yield result
}
```

## Schema & queries

So you still need to create table to store locks there and better don't let app to do that, especially concurrently from different nodes.
At [Evolution Gaming](https://www.evolutiongaming.com) this table is created along with keyspace creation by ops department.

```cql
CREATE TABLE IF NOT EXISTS keyspace.locks(
  id TEXT PRIMARY KEY,
  expiry_ms BIGINT,
  timestamp TIMESTAMP,
  metadata TEXT);

-- Acquire lock
INSERT INTO keyspace.locks (id, expiry_ms, timestamp, metadata) VALUES (?, ?, ?, ?)
  IF NOT EXISTS USING TTL ?;

-- Release lock
-- This DELETE should also be a LWT op as mixing LWT and normal ops is prohibited
DELETE FROM keyspace.locks WHERE id = ? IF EXISTS;
```

## Setup

```scala
addSbtPlugin("com.evolution" % "sbt-artifactory-plugin" % "0.0.2")

libraryDependencies += "com.evolutiongaming" %% "cassandra-sync" % latestVersion
```
