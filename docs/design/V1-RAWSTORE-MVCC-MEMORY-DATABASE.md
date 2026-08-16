# V1 RawStore MVCC memory-database completion

## Decision

`delos_mvcc` supports Derby's inherited `memory:` storage lifecycle. Memory databases do
not receive a second MVCC store, a heap-only shortcut, a filesystem shadow, or a process-global
runtime lookup.

```text
jdbc:derby:memory:<name>
    -> VFMemoryStorageFactory
    -> one database-scoped virtual DataStore
    -> inherited RawStore pages, log, undo, commit, and catalog
    -> heap and delos_mvcc access methods over the same memory namespace
```

Native or foreign-memory storage is not selected here. The initial converged memory database remains
heap-backed and uses Derby's established virtual-file implementation.

## Complete feature surface

The executable lane covers one memory database with:

```text
heap and RawStore-backed MVCC tables
mixed heap/MVCC commit
secondary and unique indexes
DML savepoint rollback
transactional MVCC DDL rollback
ordered lookup
explicit MVCC vacuum
shutdown cleanup
no filesystem database directory
```

The same production classes used for file databases perform every operation. There is no
memory-specific table, index, visibility, vacuum, transaction, or recovery algorithm.

## Named database identity

Every memory database is registered under an explicit canonical identity:

```text
memory:<canonical database namespace>
```

Canonicalization uses the same `derby.system.home` and relative-name rules as
`VFMemoryStorageFactory`. Diagnostics therefore distinguish two simultaneously active named memory
databases without relying on `requireSingle()` or object identity hashes.

The public diagnostics entry points are:

```text
DelosStorageDiagnosticsRegistry.mvccMemory(name)
DelosStorageDiagnosticsRegistry.mvccMemoryDatabaseMaintenanceSnapshot(name)
DelosStorageDiagnosticsRegistry.mvccMemoryDatabaseMemorySnapshot(name)
```

Shutdown removes only the matching weak registration. A second named memory database remains active
and queryable.

## Bounded database memory accounting

`VFMemoryStorageFactory` implements the neutral `DatabaseMemoryStorage` contract. One budget is owned
by the database's shared virtual `DataStore`, so heap, catalog, RawStore log, indexes, and MVCC pages
consume the same limit.

Configuration:

```text
delosdb.memory.maxBytes
```

Default:

```text
256 MiB per named memory database
```

The accounted quantity is allocated virtual-file payload block capacity. It is intentionally not a
whole-JVM heap estimate. The implementation:

```text
reserve budget before allocating new byte-array blocks
reject growth before usedBytes can exceed limitBytes
release accounted capacity on truncate, file deletion, and store purge
retain a peak byte count and rejected-growth count
report the current virtual entry count
```

Invalid or non-positive limits fail database initialization closed. A configured limit below already
accounted database payload also fails closed.

Immutable diagnostics use `DelosDatabaseMemorySnapshot` and include:

```text
database identity
runtime activity
memory-database flag
configured limit
current and peak accounted bytes
rejected growth count
virtual entry count
```

File databases report no memory-storage accounting.

## Shutdown and artifact rules

A memory database may create virtual directories and files only inside its inherited `DataStore`.
It must not create a path named after the database under the process working directory or
`derby.system.home`.

Normal shutdown:

```text
unregister named diagnostics identity
stop database-owned maintenance
close the RawStore MVCC runtime
release the inherited memory database when Derby drops its virtual service root
```

Diagnostics remain weak and non-owning.

## Permanent evidence

```text
:delosdb-tests:runDelosMvccRawStoreMemoryDatabaseTest
delosMvccRawStoreMemoryDatabaseStaticAnalysis
```

The focused test also exercises the public `DatabaseMemoryStorage` contract directly with an 8 KiB
budget, proving reserve-before-growth, deterministic rejection, peak accounting, and release after
file deletion.

## Non-goals

This design does not:

- make native memory or mapped memory the default;
- add a memory-specific WAL, checkpoint, recovery pass, or index;
- persist memory databases across JVM termination;
- add cross-database memory borrowing;
- change file-database cache sizing;
- change module ownership boundaries.
