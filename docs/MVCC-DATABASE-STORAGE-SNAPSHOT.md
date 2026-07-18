# MVCC Database Storage Snapshot

Phase 9 introduces `DelosDatabaseStorageSnapshot` as the first versioned,
immutable observability model.

The snapshot is owned by one `MvccDatabaseRuntime` and contains:

* explicit provider and normalized database identity;
* schema version and collection-semantics identifier;
* monotonically increasing capture sequence;
* runtime and table-state counts;
* database-local mutation and scan counters;
* database-local ordered-index and committed-read counters;
* bounded storage-path decision history and dropped-entry count;
* database commit decision-force and participant-publication timing.

## Collection semantics

The v1 snapshot declares:

```text
weakly-consistent-atomic-counters-with-bounded-path-history
```

Each counter is read atomically. Storage activity may continue between field
reads, so the snapshot is not a stop-the-world transaction. The path history is
copied under its own lock and is capped at 256 entries. Old entries are evicted
and counted rather than allowing diagnostics to grow without bound.

Counters and capture sequence describe the current database-runtime lifetime. A
clean shutdown and reopen starts a new observation epoch for the same database
identity; durable database state is not inferred from these counters.

The snapshot exposes only immutable values. It contains no table, transaction,
store, lock, or maintenance-service object and cannot change engine state.

## Compatibility surface

Existing `DelosStorageDiagnostics` counter methods remain temporarily available.
The MVCC implementation now derives their reads from the database snapshot and
routes resets to the owning database runtime. They no longer observe JVM-wide
bridge counters.

A diagnostics instance bound through:

```java
DelosStorageDiagnosticsRegistry.mvcc(databaseDirectory)
```

also clears only that database runtime during test cleanup. It does not close
other active databases.

## Next snapshots

This milestone establishes the database identity, versioning, immutability,
bounded-history, and collection-semantics conventions. Table, transaction,
version-chain, recovery, checkpoint, maintenance, and buffer-pool snapshots will
build on the same contract rather than adding more forwarding methods.
