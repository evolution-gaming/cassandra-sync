# cassandra-sync

[![Build Status](https://github.com/evolution-gaming/cassandra-sync/workflows/CI/badge.svg)](https://github.com/evolution-gaming/cassandra-sync/actions?query=workflow%3ACI)
[![Coverage Status](https://coveralls.io/repos/evolution-gaming/cassandra-sync/badge.svg)](https://coveralls.io/r/evolution-gaming/cassandra-sync)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/a804ed95dc044adb83f56411d7bacffc)](https://app.codacy.com/gh/evolution-gaming/cassandra-sync/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)
[![Version](https://img.shields.io/badge/version-click-blue)](https://evolution.jfrog.io/artifactory/api/search/latestVersion?g=com.evolution&a=cassandra-sync_3&repos=public)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellowgreen.svg)](https://opensource.org/licenses/MIT)

This tiny library provides mechanism of `synchronization` via locks stored in Cassandra table.
This `synchronization` is especially useful for preventing concurrent schema changes.
Sad that concurrent schema changes [is not supported](https://issues.apache.org/jira/browse/CASSANDRA-10699) by
Cassandra on it's own.

The library is built on [scassandra](https://github.com/evolution-gaming/scassandra) (`scassandra-v4`),
i.e. it uses the DataStax Java driver 4.

## Concept

Basically this is a mutex implementation via records stored in cassandra.
We insert record in order to acquire the lock and remove record when done.
We also provide expiry duration per lock in case we failed to remove record.
The expiry is applied as the record TTL in Cassandra, so it is rounded up to whole seconds,
and expiry below 1 second results in 1 second TTL.
There is nice [statement](https://stackoverflow.com/a/34558/301517) which ideally describes the concept.

Each lock acquisition is tagged with a unique acquisition ID stored in the record. This makes the lock operations safe
in the following cases:

* the record is removed only if it still belongs to the same acquisition, so if the lock expired and got taken by
  another contender, releasing the stale lock doesn't remove the record of the new owner
* the acquisition query can be safely retried: if a previous attempt timed out but got applied in Cassandra,
  the retry recognizes the existing record as its own instead of waiting for it to expire

## Usage

```scala
def example(session: CassandraSession[IO]) = {
  for {
    cassandraSync <- CassandraSync.of(session, keyspace = "app")
    result <- cassandraSync("id", expiry = 3.seconds, timeout = 10.seconds) {
      // put your code here 
      // the lock in cassandra per id will ensure that your code runs strictly sequentially
    }
  } yield result
}
```

## Schema & queries

So you still need to create table to store locks there and better don't let app to do that, especially concurrently from
different nodes.
At [Evolution](https://www.evolution.com) this table is created along with keyspace creation by ops
department.

```cql
CREATE TABLE IF NOT EXISTS keyspace.locks(
  id TEXT PRIMARY KEY,
  acquisition_id UUID,
  expiry_ms BIGINT,
  timestamp TIMESTAMP,
  metadata TEXT);

-- Acquire lock
-- If not applied, the acquisition_id of the existing record is checked to tell whether it is our own lock
INSERT INTO keyspace.locks (id, acquisition_id, expiry_ms, timestamp, metadata) VALUES (?, ?, ?, ?, ?)
  IF NOT EXISTS USING TTL ?;

-- Release lock
-- This DELETE should also be a LWT op as mixing LWT and normal ops is prohibited
DELETE FROM keyspace.locks WHERE id = ? IF acquisition_id = ?;
```

Tables created by cassandra-sync 4.x, i.e. without the `acquisition_id` column, are still supported,
see [Migration from 4.x to 5.x](#migration-from-4x-to-5x).

## Setup

```scala
addSbtPlugin("com.evolution" % "sbt-artifactory-plugin" % "0.1.2")

libraryDependencies += "com.evolution" %% "cassandra-sync" % latestVersion
```

## Migration from 4.x to 5.x

Version 5.x moves from the DataStax Java driver 3 to the driver 4, which comes with the following notable changes:

* the organization is changed from `com.evolutiongaming` to `com.evolution`
* the package is changed from `com.evolutiongaming.cassandra.sync` to `com.evolution.cassandra.sync`
* the library depends on `"com.evolution" %% "scassandra-v4"` instead of `"com.evolutiongaming" %% "scassandra"`,
  so `CassandraSync.of` expects `com.evolution.scassandra.CassandraSession`,
  see the [scassandra](https://github.com/evolution-gaming/scassandra) docs on how to create it
* `CassandraSync.of` requires `Async[F]` instead of `Temporal[F]`
* the lock implementation is now internal, `CassandraSync.of` is the only way to create an instance:
    * `CassandraSync.apply(interval, statements)`, `CassandraSync.Insert`, `CassandraSync.Delete`
      and `CassandraSync.Statements` are removed
    * the `CassandraSync.Id` type alias is removed, use `String` instead
    * the implicit `FiniteDuration` codecs `CassandraSync.finiteDurationEncodeByName` and
      `CassandraSync.finiteDurationDecodeByName` are removed, define your own if you relied on them
* a failure to release the lock no longer fails the call if `f` succeeds: the result of `f` is returned,
  the failure is logged via slf4j (logger `com.evolution.cassandra.sync.CassandraSync`), and the lock record
  remains until it expires; a lock record that has already expired on release is logged as a warning
* the lock record TTL is computed by rounding `expiry` up to whole seconds instead of down,
  e.g. `expiry = 1500.millis` now results in 2 seconds TTL instead of 1 second
* in the 4.x implementation, the sleep time between attempts (`pollInterval`) was incorrectly applied,
  effectively doubling the sleep time and sleeping even after successful attempts
* releasing a lock removes the lock record only if it belongs to the same acquisition,
  in 4.x a lock which had expired and was taken by another contender could be removed
* a timed out acquisition query which was applied in Cassandra no longer stalls the acquisition until the lock expires
* the locks table has a new `acquisition_id UUID` column, see [Schema & queries](#schema--queries)

### Locks table schema

The existing locks tables created for 4.x can be used as is. If the table has no `acquisition_id` column,
5.x falls back to storing the acquisition ID in the `metadata` column, so the value there looks like
`<acquisition id>|<metadata>` instead of just `<metadata>`. This mode is compatible with 4.x instances
using the same table, so 4.x and 5.x instances can share the table during a rolling upgrade.

It is recommended to add the column once all the instances are upgraded to 5.x:

```cql
ALTER TABLE keyspace.locks ADD acquisition_id UUID;
```

Please note:

* `AutoCreate` doesn't add the column to an existing table, as it uses `CREATE TABLE IF NOT EXISTS`
* the schema is checked only in `CassandraSync.of`, so the already created `CassandraSync` instances keep using
  the fallback mode until recreated, e.g. on application restart; instances in both modes can work with the same
  table at the same time
