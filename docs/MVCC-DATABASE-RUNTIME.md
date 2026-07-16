# MVCC Database Runtime Ownership

## Purpose

Each open Derby database owns one explicit MVCC runtime. The runtime replaces the former mutable
process-global database-directory selection and prevents one database from opening, mutating,
diagnosing, or closing another database's MVCC state.

## Owner

```text
MvccConglomerateFactory
    → MvccDatabaseRuntime.Lease
        → MvccDatabaseRuntime
            → DelosStorageStore
            → table-state registry
```

The factory acquires a reference-counted lease for its canonicalized Derby database directory during
boot. Every conglomerate created or reopened by that factory receives the runtime explicitly.

## Runtime responsibilities

`MvccDatabaseRuntime` owns:

```text
canonicalized database directory
one DelosStorageStore
one bridge table-state registry
database-scoped lifecycle
explicit diagnostics lookup
```

The provider store continues to own the database maintenance service and backup-coordinator lease.
The runtime is the bridge-level owner that binds Derby conglomerates to that store.

## Lifecycle

```text
RAMAccessManager boots external MVCC factory
    → factory acquires runtime lease
    → conglomerates bind to runtime
    → table states open through runtime store

Derby database shutdown
    → RAMAccessManager stops external factories
    → factory releases runtime lease
    → last lease closes store and table states
```

External access methods are tracked explicitly by the owning `RAMAccessManager` because they are
booted through `ServiceLoader`, not as inherited monitor-owned child modules.

## Persistent descriptors

Serialized conglomerate metadata contains only the existing durable descriptor fields. Runtime and
table-state references are transient. Normal reopen uses the owning factory and container key to
construct a runtime-bound conglomerate.

A descriptor read without a runtime may restore metadata, but it cannot perform table operations
until it has been attached through the database factory context.

## Diagnostics

MVCC diagnostics accept an explicit `DelosStorageDiagnosticsContext` containing the database
directory. SQL tests, metadata targets, explicit statistics lifecycle observations, and optimizer
metadata observations bind that context to the database they are inspecting.

The integration suite may keep many Derby databases booted in one JVM. That is not a valid reason
to choose one of them implicitly. When more than one database runtime is active, unbound table
diagnostics reject instead of guessing which database owns a container identifier.

This rule prevents a container number from being interpreted through whichever database happened
to boot most recently.

## Lazy access-method activation

Derby discovers external conglomerate factories lazily. Reopening a database and reading only its
system catalogs does not by itself open a persisted MVCC conglomerate or acquire the database's
MVCC runtime. The runtime becomes active when Derby opens an MVCC conglomerate through the normal
transaction-controller path.

The embedded SQL integration helper therefore performs one explicit reopen step for existing
databases:

```text
read persisted conglomerate identifiers from SYS.SYSCONGLOMERATES
select identifiers owned by MVCC factory id 2
open and close each conglomerate controller with ISOLATION_NOLOCK
return the connection without scanning rows or changing diagnostic counters
```

This is test-harness lifecycle preparation, not a production diagnostics fallback. Diagnostics still
reject an inactive or ambiguous runtime rather than opening an arbitrary database. Phase 9's
structured snapshot work may later define a separate durable offline-inspection contract.

## Invariants

```text
no mutable process-global database path
one canonicalized identity per runtime registry entry
one provider store per active database runtime
all conglomerates bind through their factory runtime
shutdown of database A does not close database B
state files for a table remain under its owning database directory
diagnostics never select a database through ambient state
metadata and optimizer diagnostic requests carry database identity
database-specific lifecycle assertions ignore unrelated booted databases
```

## Focused proof

`MvccSqlDatabaseRuntimeIsolationTest` opens two databases concurrently and verifies:

```text
create and write in database A
boot and write database B
create another table in A after B is active
state files remain under the correct database roots
bound diagnostics report independent state counts
shutdown A while B continues writing
reopen A while B remains active
shutdown and reopen both without crossed state
reopen activates persisted MVCC conglomerates before runtime diagnostics
full SQL integration can inspect many booted databases without ambient selection
```

## Scope boundary

This runtime fixes database ownership. It does not implement the Phase 8 database-level atomic
transaction decision for multi-table or mixed heap/MVCC writes.
