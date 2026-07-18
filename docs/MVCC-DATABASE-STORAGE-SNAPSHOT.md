# MVCC database storage snapshots

Phase 9 introduces immutable, versioned observations for one database runtime,
its provider-owned tables, and active provider transaction participants.

The root model is:

```text
DelosDatabaseStorageSnapshot schema version 2
```

It carries explicit provider and normalized database identity, a runtime-local
capture sequence, one shared root/nested capture timestamp, database counters,
commit timing, and bounded nested observations:

```text
storage path diagnostics: 256 entries
table snapshots:          256 entries
transaction snapshots:    512 entries
```

When a bounded collection exceeds its capacity, the oldest path entries are
evicted and counted, while table and transaction observations are sorted by
stable table identity and truncated with explicit dropped-entry counts.

## Collection semantics

Database counters use:

```text
weakly-consistent-database-counters-with-bounded-table-transaction-and-path-observations
```

Each counter is read atomically, but concurrent work may occur between reads.
The storage-path history is copied under its own lock.

Each `DelosTableStorageSnapshot` uses:

```text
weakly-consistent-table-diagnostics-with-registry-participants
```

Provider diagnostics are individually protected by the table's existing read
lock, but concurrent work may occur between fields. Transaction membership is
copied atomically from the provider-neutral registry before the table values
are read. The table observation includes transaction-registry participant
counts, registered write intents, logical and physical row/version counts, page
topology, ordered-index size, purge backlog, checkpoint status, and
consistency status.

Each `DelosTransactionSnapshot` is a value-only observation of one active
provider transaction participant. It identifies the owning database and table,
provider transaction ID, read-only/read-write mode, write intents, savepoints,
and write-intent revision. Participant membership comes from the
provider-neutral transaction registry, while the opaque provider handle supplies
one atomic, value-only counter bundle. The model does not expose the Derby
transaction object, provider handle, or a mutable snapshot.

## Ownership and authority

All observations are owned by one `MvccDatabaseRuntime`. Controllers and scans
record into that runtime; table state assembles immutable values through the
existing diagnostic and transaction-registry boundaries.
A database-bound diagnostics handle can clear only its own runtime in testing.
It cannot close or reset another active database.

The models are diagnostic evidence only. They expose no operation that can
change storage, transaction, checkpoint, maintenance, or recovery state.
Runtime counters and provider transaction IDs describe one database-runtime
epoch and are not persisted across reopen.
