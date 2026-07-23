# DelosDB v1 RawStore convergence architecture

## Status

This document records the approved DelosDB v1 storage direction.

The architectural invariants are frozen. Exact Java interfaces, page formats, transaction hooks,
container layouts, and migration mechanics remain provisional until their design proofs and vertical
slices are complete.

Phase 8 established a correct independent MVCC persistence system with database-level transaction
decision authority and transactional DDL. That implementation remains the current correctness
baseline while convergence is developed. It is not the final v1 storage format.

## Product identity

DelosDB v1 has one physical and transactional database storage authority:

```text
Derby SQL / JDBC / DRDA / catalog
                |
       AccessFactory / TransactionManager
                |
             RawStore
        +-------+-------+
        |               |
   heap access      MVCC access
     method           method
```

Heap and MVCC are peer access methods over the same RawStore.

Lucene is different:

```text
RawStore data and full-text journal: authoritative
Lucene segment files:                derived and rebuildable
```

Lucene never becomes a second transaction authority.


## Implemented convergence status

Stages 2.1 and 2.2 are implemented.

`RAMAccessManager` creates a database-owned `AccessMethodBootContext` and passes the actual RawStore,
DataFactory, StorageFactory, and opaque database-service identity to external access methods. The
MVCC factory directly owns one runtime; the former static `acquire(Path)`/lease registry and
`PersistentService.ROOT` reconstruction are removed.

`RAMTransaction` now owns identity-keyed `AccessMethodTransactionLifecycle` participants and brackets
the inherited RawStore commit, commitNoSync, abort, savepoint, nested-user-transaction, XA, and
destroy paths. This is a semantic extension seam, not another commit coordinator.

The complete RawStore-backed table path is now proven through maintenance and diagnostics. Stage 5.1
is user-verified: RawStore mode and retained Phase 8 mode are mutually exclusive inside one factory
boot. RawStore mode no longer constructs the retained runtime or opens its provider store; retained
files fail closed before the RawStore runtime starts. Stage 5.2 now retargets the permanent mixed
heap/MVCC power-loss proof to the inherited RawStore commit and recovery boundaries, with no retained
decision journal or copied-log restoration. The retained implementation remains available only while
its remaining fault and recovery suites are retargeted.

Implementation records:

```text
docs/design/V1-DATABASE-OWNED-ACCESS-METHOD-BOOT.md
docs/design/V1-ACCESS-METHOD-TRANSACTION-LIFECYCLE-SEAM.md
docs/design/V1-RAWSTORE-MVCC-AUTHORITY-CUTOVER.md
docs/design/V1-RAWSTORE-MVCC-DECISION-RECOVERY-CUTOVER.md
```

## Frozen v1 invariants

1. One RawStore transaction outcome governs heap, MVCC, catalog, indexes, transactional DDL, and
   derived-index journal records.
2. MVCC writes occur as RawStore mutations during statement execution, not as a separate store
   published only at commit.
3. RawStore owns containers, pages, allocation, buffer management, WAL, undo, checkpoint, recovery,
   backup, file storage, and memory storage.
4. MVCC owns transaction/version identities, snapshots, visibility, conflicts, stable logical row
   identity, history retention, version-aware index semantics, and vacuum eligibility.
5. RawStore internal transaction identifiers are not durable MVCC identities. DelosDB retains
   explicit `MvccTransactionId` and `MvccCommitSequence` semantics.
6. Production MVCC code ultimately owns no paths, files, page volumes, independent WAL, checkpoint,
   recovery subsystem, backup coordinator, decision retention, or second commit coordinator.
7. Derby memory databases use the same database storage lifecycle for heap, MVCC, catalog, indexes,
   journals, and transaction metadata. No hidden temporary filesystem is permitted.
8. The engine depends only on provider-neutral APIs. MVCC and Lucene do not compile against engine
   implementation classes.
9. Lucene is maintained from a transactional RawStore journal and is always rebuildable from
   authoritative database state.
10. JDK 25 physical-storage modernization happens after RawStore convergence so it benefits both
    heap and MVCC.

## Current transitional truth

The retained Phase 8 implementation still contains independent page files, WAL/checkpoint/status
artifacts, recovery machinery, and bridge/provider infrastructure. Those classes are not deleted
merely because the target architecture is approved.

They are now reachable only when RawStore mode is disabled. An opted-in RawStore boot rejects retained
provider files instead of booting both authorities. The remaining retained components stay available
until their fault, recovery, reopen, and operational gates are retargeted to RawStore boundaries.

The migration must not create a lifecycle-only RawStore shell around table data that remains
authoritative in external MVCC files.

## RawStore and access-method ownership

### RawStore decides

```text
where bytes live
container and page allocation
database and log storage namespaces
buffer-cache ownership
physical mutation logging
undo and rollback
commit record
checkpoint and recovery
backup boundary
file versus memory implementation
```

### Heap decides

```text
heap record layout
locking behavior
heap scans and updates
heap-specific maintenance
```

### MVCC decides

```text
version-record semantics
snapshot visibility
write-conflict validation
logical row and version identity
version-chain traversal
version-aware scans and indexes
history retention and vacuum eligibility
```

## MVCC mutation rule

The final architecture performs physical MVCC changes while the SQL statement executes:

```text
INSERT / UPDATE / DELETE
    -> RawStore container/page mutation
    -> RawStore WAL and undo
    -> uncommitted MVCC state remains invisible to other transactions
```

Commit finalizes MVCC visibility metadata and then uses the normal RawStore commit record.
Rollback and savepoint rollback use RawStore undo plus transaction-local semantic bookkeeping.

A commit-time publication layer that replays every MVCC row and index change into a second physical
system is not part of the final architecture.

## Durable identity and snapshot visibility

`MvccTransactionId` and `MvccCommitSequence` remain DelosDB concepts, but their allocation and
persistent state move into RawStore-owned metadata.

For local transactions, the first approved design proof uses a database-scoped commit-publication
boundary:

```text
finalize one transaction
    -> reserve commit sequence
    -> stamp MVCC visibility and derived-journal metadata
    -> commit RawStore transaction
    -> publish committed high-water mark
```

Snapshot acquisition coordinates with the same boundary and captures only a published committed
high-water mark. A transaction whose commit has not completed cannot become visible to an already
opened snapshot merely because it reserved a lower sequence number.

The exact integration API is not frozen. XA prepared-transaction behavior remains unresolved and
must fail closed until a complete proof exists.

The accepted transaction-lifecycle proof additionally requires RawStore-first savepoint rollback,
idempotent error cleanup, fail-closed MVCC behavior for XA and nested update transactions, and a
separate snapshot lease for a held MVCC cursor that survives commit.

See:

```text
docs/design/V1-MVCC-COMMIT-ORDERING-AND-SNAPSHOT-MATHEMATICS.md
docs/design/V1-DERBY-TRANSACTION-LIFECYCLE-MATRIX.md
```


## Accepted first RawStore physical slice

The first MVCC physical proof uses existing RawStore slotted pages rather than a new Delos page
format.

```text
MVCC table
    +-- metadata and stable-row directory container
    +-- version container
```

Both containers are created and mutated by the caller's RawStore transaction. Normal RawStore page
inserts and field updates provide logging, savepoint/abort undo, checkpoint participation, crash
recovery, and inherited file/memory operation.

The directory maps logical `MvccRowId` to logical `MvccVersionId`. A `RecordHandle` may be retained as
an ephemeral transaction-local locator for commit stamping, but it is not the durable identity.

The isolated proof deliberately permits linear logical-ID scans and conservative locking. It adds no
custom `Loggable`, external page file, page volume, or second recovery path.

See:

```text
docs/design/V1-RAWSTORE-MVCC-VERTICAL-SLICE.md
```

## Stable row and version identity

The accepted identity model is logical and table-scoped:

```text
complete row identity     = MVCC table incarnation + MvccRowId
complete version identity = MVCC table incarnation + MvccVersionId
version-chain edge        = previousVersionId
```

A committed row/version identity is never reused in the same table incarnation. Updates and deletes
preserve the row ID and allocate a new version ID. Truncate or provider-preserving rebuild cannot
reset allocator high-water unless the operation creates a new table incarnation.

RawStore locations may be cached or persisted only as validated hints. A hint may include the
expected container, page number, and page-local record identifier. A slot number is never identity.
The stored row/version header must match before a hint is trusted; otherwise the access method falls
back to logical lookup and may repair the hint.

Vacuum rewrites the successor's logical predecessor link in the same RawStore transaction before it
purges an interior or prefix version. A row directory is transactionally maintained but rebuildable
from the authoritative version records.

See:

```text
docs/design/V1-MVCC-STABLE-ROW-AND-VERSION-IDENTITY.md
```

## Memory databases

After MVCC table state resides in RawStore containers, this must work:

```java
DriverManager.getConnection("jdbc:derby:memory:delos;create=true");
```

```sql
CREATE TABLE customer (
    id BIGINT PRIMARY KEY,
    name VARCHAR(200)
) USING delos_mvcc;
```

The first implementation uses Derby's inherited memory-database storage lifecycle. Native or mapped
`MemorySegment` modernization is a later shared RawStore change, not an MVCC-only backend.

## Lucene boundary

The final Lucene implementation is optional derived state:

```text
base-row change
    + transactional full-text journal record
    + one RawStore commit
```

After commit, the Lucene provider applies journal entries idempotently and records an applied
watermark. A Lucene failure never rolls back an already committed SQL transaction.

Strong-search semantics, read-your-writes behavior, watermark crash recovery, journal retention,
backpressure, and DDL lifecycle remain design-proof items. Legacy Lucene 4 APIs are not a v1
compatibility contract.

## Provisional implementation details

The following are intentionally not frozen:

```text
AccessMethodBootContext fields
commit-sequence allocator implementation
XA prepared-transaction visibility
version-record binary header
version-link representation
RawStore page/container format
number of containers per MVCC table
ordered-index physical format
full-text journal payload
Lucene strong-search locking
Lucene CREATE/DROP state machine
exact package and class names
```

## Migration programme

1. Complete the design proofs.
2. Introduce only the neutral provider/store seams proven necessary.
3. Build one complete RawStore-backed MVCC vertical slice:

   ```text
   CREATE
   INSERT
   point read
   COMMIT
   ROLLBACK
   reopen
   crash recovery
   memory database
   ```

4. Extend that format to updates, deletes, chains, indexes, overflow, savepoints, and vacuum.
5. Remove the independent MVCC persistence system only after parity and recovery gates pass.
6. Absorb the bridge and obsolete storage modules after their responsibilities have moved.
7. Complete storage-module convergence: Stage 7.1 removed the bridge, Stage 7.2 removed
   storage-io, and Stage 7.3 removed storage-api. Add `delosdb-search-lucene` to reach the frozen
   21-subproject target.
8. Modernize shared JDK 25 file and memory storage.
9. Replace legacy Lucene with a neutral optional provider over transactional RawStore journals.
10. Capture a new v1 baseline only after the converged architecture is complete.

## Required architecture gates

The final v1 tree must prove:

```text
no direct filesystem API in production MVCC code
no path-keyed MVCC runtime
no independent MVCC WAL
no independent MVCC checkpoint
no independent MVCC transaction decision
no immediate physical DDL deletion
no engine dependency on MVCC or Lucene implementation
no MVCC or Lucene dependency on engine implementation
no Lucene 4 runtime or public API
no static Lucene database registry
exact 21-subproject final target
no storage-api, storage-io, or storage-bridge in final settings.gradle
MVCC and Lucene remain separate provider artifacts
storage-derby remains a build-only patch artifact
```

Stage 2.3 installs `delosV1ModuleArchitectureStaticAnalysis`, backed by the machine-readable
`gradle/static-analysis/delosdb-v1-final-module-target.txt` contract. It enforces the current neutral
provider boundary now and remains migration-aware until the final module-removal gates become active.


## Stage 5 decision and recovery proof cutover

The permanent mixed heap/MVCC crash lane now proves the converged authority directly. A child JVM
mutates a heap table and a RawStore-backed MVCC table in one JDBC transaction, then halts immediately
before or immediately after the inherited RawStore commit record. Normal Derby reopen is the only
recovery pass: both mutations roll back before the commit and both survive after it.

The proof no longer installs the retained failure registry through reflection, copies or restores the
Derby log directory, or inspects retained transaction-decision files. It also verifies that the
RawStore database contains no retained MVCC regular files. This retires the first permanent Phase 8
fault-proof dependency without yet deleting the retained implementation.


## Stage 5 SQL transaction-authority proof cutover

The permanent SQL multi-table transaction lane now explicitly enables RawStore-backed MVCC. Two MVCC
tables share one inherited RawStore transaction for commit and rollback. A heap table and multiple MVCC
tables also share that same outcome and survive normal shutdown/reopen together. The proof creates no
retained `delos_mvcc/inherited-store` file.

The historical `runDelosMvccDatabaseCommitDecisionTest` task remains only as a compatibility alias for
`runDelosMvccRawStoreSqlTransactionCutoverTest`; it no longer selects retained decision-journal tests.

## Stage 5 retained production runtime retirement

RawStore is now the unconditional `delos_mvcc` production authority. The retained runtime/controller
classes are excluded from the active bridge artifact, the retained MVCC/page-volume jars are outside
root assembly and normal runtime/test classpaths, and the archived suite is available only through
`legacyRetainedCheck`. Existing retained files fail closed and require an external migration path;
they are never dual-read or mutated.

## Stage 6 named memory databases and bounded accounting

Named `memory:` databases now use the complete RawStore MVCC feature set through
`VFMemoryStorageFactory`. Heap and MVCC tables, mixed transactions, indexes, savepoints,
transactional DDL, and vacuum share one inherited database storage namespace and create no filesystem
database directory.

Memory diagnostics use an explicit canonical `memory:` identity. Two named memory databases can remain
booted simultaneously, be observed independently, and shut down without affecting each other.

The neutral `DatabaseMemoryStorage` contract bounds allocated virtual-file payload capacity per
database. `delosdb.memory.maxBytes` defaults to 256 MiB. Capacity is reserved before block allocation,
rejected before the bound is exceeded, and released on truncate, deletion, and purge. Immutable
`DelosDatabaseMemorySnapshot` observations expose current and peak bytes, the configured limit,
rejected growth, and entry count.

See `design/V1-RAWSTORE-MVCC-MEMORY-DATABASE.md`.


## Stage 8.1 shared positional page I/O

The common `StorageRandomAccessFile` boundary now defines pointer-stable positional reads and writes
and an explicit `force(metadata)` operation. The directory implementation maps these operations to
`FileChannel.read`, `FileChannel.write`, and `FileChannel.force`; virtual memory uses the compatible
default implementation and no-op durability.

Both inherited RawStore container variants route normal page I/O through this boundary. The existing
`RAFContainer4` interrupt-driven channel reopen/retry protocol remains authoritative, while its
duplicate full-page transfer loop is removed. The change affects the shared heap/MVCC physical path
without changing page bytes, allocation, caching, logging, or recovery.

```text
StorageRandomAccessFile positional contract
    -> directory FileChannel implementation
    -> virtual-memory compatibility implementation
    -> RAFContainer and RAFContainer4
    -> inherited heap and RawStore-backed MVCC
```

See `design/V1-JDK25-SHARED-POSITIONAL-IO.md`.

## Stage 8.2 shared RawStore I/O diagnostics

`BaseDataFileFactory` now owns one bounded `DelosRawStoreIoMetrics` object for the physical page path.
`RAFContainer` and `RAFContainer4` publish completed page bytes, transfer failures, explicit force
requirements, closed-channel recovery, in-flight operations, and long-lived container-handle lifetime.
The counters do not participate in page locking, cache replacement, transaction outcome, or recovery.

Heap and RawStore-backed MVCC diagnostics expose the same immutable `DelosRawStoreIoSnapshot`. File and
named memory databases use explicit canonical identities, and simultaneously active databases remain
isolated. A weak active lookup connects heap diagnostics to the database-owned metrics without extending the
runtime lifetime. A bounded terminal cache retains only recent immutable shutdown snapshots, never
per-operation event history.

```text
BaseDataFileFactory-owned metrics
    -> RAFContainer / RAFContainer4 shared positional path
    -> one immutable snapshot
    -> heap diagnostics and MVCC diagnostics
```

See `design/V1-JDK25-SHARED-RAWSTORE-IO-DIAGNOSTICS.md`.
