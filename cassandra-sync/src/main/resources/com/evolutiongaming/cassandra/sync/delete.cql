-- Release lock
-- This DELETE should also be a LWT op as mixing LWT and normal ops is prohibited
DELETE FROM keyspace.locks WHERE id = ? IF EXISTS;
