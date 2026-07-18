# V1 database-owned external access-method boot

Status: IMPLEMENTED — Stage 2.1

## Purpose

DelosDB external access methods must be owned by the same booted database service that owns RawStore.
They must not reconstruct database identity from a service-name string or acquire a process-global
runtime by filesystem path.

This seam is the first production step toward heap and MVCC becoming peer access methods over one
RawStore. It changes ownership only. It does not yet move MVCC table data into RawStore containers.

## Boot flow

```text
RAMAccessManager boots RawStore
    -> resolves the owning DataFactory
    -> obtains the owning StorageFactory
    -> creates one AccessMethodBootContext
    -> discovers ExternalAccessMethodProvider
    -> passes the context to the provider
    -> provider boots MvccConglomerateFactory
    -> factory creates and owns one MvccDatabaseRuntime
```

`AccessMethodBootContext` carries:

```text
RawStoreFactory
DataFactory
StorageFactory
service properties
create/read-only state
database-service identity
```

The database identity is opaque. An access method may retain it for identity comparison but may not
turn it into a path or use it as a process-global registry key.

## Runtime lifecycle

`MvccConglomerateFactory` directly owns one runtime. `RAMAccessManager` already retains lifecycle
owners for service-loaded access methods and calls `stop()` during database shutdown. Factory stop
closes only its runtime.

Removed ownership mechanisms:

```text
MvccDatabaseRuntime.acquire(Path)
MvccDatabaseRuntime.Lease
static reference counts
static path-keyed runtime ownership map
PersistentService.ROOT lookup inside MVCC
```

Closing one database therefore cannot decrement, select, or close another database's runtime through
a shared ownership registry.

## Transitional physical storage

The current Phase 8 MVCC implementation still persists independent files. During Stage 2.1 only, the
legacy directory is obtained from the context-owned `DataFactory.getRootDirectory()`.

That path is a temporary physical location, not database identity. It disappears when MVCC table data
moves to RawStore containers.

Memory and other non-directory databases fail with SQLState `0A000` before the legacy external store
opens. This avoids hidden temporary files and false memory-database support. Real MVCC memory support
begins with the RawStore-backed table format.

## Diagnostics

Existing test and diagnostic APIs identify a database by directory. During convergence, a small weak
lookup connects those APIs to an already-owned runtime.

The lookup is deliberately non-owning:

```text
weak references only
no acquisition
no reference counting
no close operation
no runtime creation
```

Production execution never selects a runtime through this diagnostic lookup.

## Permanent contract

The runtime-ownership static gate requires:

```text
RAMAccessManager constructs AccessMethodBootContext
ExternalAccessMethodProvider accepts the context
MVCC does not read PersistentService.ROOT
MvccDatabaseRuntime has no acquire/lease/static ownership registry
MvccConglomerateFactory directly owns one runtime
legacy diagnostic lookup is weak and non-owning
memory databases fail closed before legacy MVCC storage opens
```

## Next step

Stage 2.2 derives the smallest neutral transaction-lifecycle seam from the accepted Derby lifecycle
matrix. It must not migrate table data or revive the rejected five-method participant sketch.
