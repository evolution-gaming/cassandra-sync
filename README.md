# cassandra-sync [![Build Status](https://travis-ci.org/evolution-gaming/cassandra-sync.svg)](https://travis-ci.org/evolution-gaming/cassandra-sync) [![Coverage Status](https://coveralls.io/repos/evolution-gaming/cassandra-sync/badge.svg)](https://coveralls.io/r/evolution-gaming/cassandra-sync) [![Codacy Badge](https://api.codacy.com/project/badge/Grade/041b527e012447b093bf3d68b4d79c67)](https://www.codacy.com/app/evolution-gaming/cassandra-sync?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=evolution-gaming/cassandra-sync&amp;utm_campaign=Badge_Grade) [ ![version](https://api.bintray.com/packages/evolutiongaming/maven/cassandra-sync/images/download.svg) ](https://bintray.com/evolutiongaming/maven/cassandra-sync/_latestVersion) [![License: MIT](https://img.shields.io/badge/License-MIT-yellowgreen.svg)](https://opensource.org/licenses/MIT)

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
resolvers += Resolver.bintrayRepo("evolutiongaming", "maven")

libraryDependencies += "com.evolutiongaming" %% "cassandra-sync" % "0.0.1"
```
