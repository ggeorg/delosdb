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

The current MVCC implementation still has independent page files, WAL/checkpoint/status artifacts,
recovery machinery, and bridge/provider infrastructure. Those components are not deleted merely
because the target architecture is approved.

They remain in service until a complete RawStore-backed replacement passes the corresponding
transaction, recovery, reopen, memory-database, and differential gates.

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

See `docs/design/V1-MVCC-COMMIT-ORDERING-AND-SNAPSHOT-MATHEMATICS.md`.

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
transaction participant interface
complete transaction lifecycle callbacks
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
7. Modernize shared JDK 25 file and memory storage.
8. Replace legacy Lucene with a neutral optional provider over transactional RawStore journals.
9. Capture a new v1 baseline only after the converged architecture is complete.

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
```
