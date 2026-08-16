# V1 database-owned external access-method boot

Status: IMPLEMENTED

## Purpose

External access methods are owned by the same booted database service that owns RawStore. They do
not reconstruct database identity from a filesystem path and do not acquire process-global storage
runtimes.

## Boot flow

```text
RAMAccessManager boots RawStore
    -> resolves the owning DataFactory and StorageFactory
    -> creates one AccessMethodBootContext
    -> discovers ExternalAccessMethodProvider
    -> passes the database-owned context to the provider
    -> provider boots MvccConglomerateFactory
    -> factory creates and owns one MvccRawStoreRuntime
```

`AccessMethodBootContext` carries the database-owned RawStore and service context required by an
external access method without exposing implementation-global ownership.

## Runtime lifecycle

`MvccConglomerateFactory` owns one `MvccRawStoreRuntime` for the booted database. The runtime binds
MVCC table descriptors, transaction identity, commit-sequence publication, maintenance, diagnostics,
and shutdown to that database.

Factory shutdown closes only the runtime owned by that database. Closing database A cannot select,
decrement, or close database B through a shared path-keyed registry.

## Physical storage ownership

The runtime does not own an independent physical store. MVCC table data, indexes, metadata, and
transaction-relevant records live in RawStore containers owned by the database lifecycle.

Named in-memory databases use the inherited RawStore memory lifecycle and do not create hidden
filesystem state.

## Diagnostics

Diagnostics resolve explicit database-owned MVCC state. They do not select a production runtime from
ambient "last opened" state or use filesystem path as execution authority.

## Permanent contract

```text
RAMAccessManager constructs AccessMethodBootContext
ExternalAccessMethodProvider accepts the context
MvccConglomerateFactory directly owns one MvccRawStoreRuntime
one booted database cannot close another database's MVCC runtime
production MVCC execution uses database-owned RawStore context
memory databases use the inherited RawStore lifecycle
```

## Transaction lifecycle

The neutral transaction-lifecycle seam connects the access method to the Derby transaction boundary.
It does not create a second transaction manager or durable commit authority.
