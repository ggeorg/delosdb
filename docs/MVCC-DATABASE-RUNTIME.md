# MVCC Database Runtime Ownership

## Purpose

Each booted Derby database owns one RawStore-backed MVCC runtime. Database
identity, table descriptors, maintenance, diagnostics, and transaction
participation are bound to that runtime rather than to mutable process-global
state or an external provider store.

## Owner

```text
MvccConglomerateFactory
    -> one MvccRawStoreRuntime
        -> RawStoreFactory / TransactionFactory
        -> RawStore-backed table descriptors
        -> MvccRawStoreMaintenanceService
        -> database-scoped diagnostics
```

There is no `DelosStorageStore`, external MVCC page volume, external WAL, or
runtime backend selector in the current path.

## Lifecycle

The access-method factory creates the runtime from the booted database context.
Every MVCC conglomerate opened by that factory resolves its descriptor and
transaction state through the same runtime. Shutdown closes the database-owned
maintenance service, clears registered descriptors and diagnostics, and then
releases the runtime.

Persistent conglomerate metadata contains only durable descriptor fields.
`MvccRawStoreRuntime` references are transient and are rebound by the owning
factory when a database reopens.

## Durability and backup

Current MVCC rows, indexes, transaction decisions, and recovery state live in
ordinary Derby RawStore containers and log records. Normal Derby backup copies
those files. Current databases do not create a `delos_mvcc` durability sidecar.

Artifacts from the retired external format are not copied or restored. A `delos_mvcc` directory,
backup manifest, or in-progress marker rejects boot, backup, and restore with SQLState `0A000`.

## Diagnostics

Diagnostics use explicit database identity and provider context. They never
select a database through ambient "last opened" state. Multiple databases may
be booted in one JVM without crossing container identities or maintenance
ownership.

## Invariants

```text
one RawStore MVCC runtime per booted database
one persistence authority: Derby RawStore
no external MVCC store or fallback
no mutable process-global database selection
maintenance and diagnostics are database-scoped
shutdown of database A cannot close database B
```

## Focused proofs

```text
:delosdb-tests:delosFunctionalTests :delosdb-tests:delosConcurrencyTests :delosdb-tests:delosRecoveryTests
:delosdb-tests:delosUnitTests --tests '*MvccRetiredSidecarRejectionTest'
:delosdb-tests:delosRecoveryTests --tests '*MixedEngineBackupRestoreMatrixTest'
```
