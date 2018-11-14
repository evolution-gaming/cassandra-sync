CREATE TABLE IF NOT EXISTS keyspace.locks(
id TEXT PRIMARY KEY,
expiry_ms BIGINT,
timestamp TIMESTAMP,
metadata TEXT);