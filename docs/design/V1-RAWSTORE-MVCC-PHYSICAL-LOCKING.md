# RawStore MVCC physical lock-granularity completion

## Decision

The RawStore-backed MVCC format separates semantic conflict identity from physical page protection.

Semantic conflicts use the transaction-duration logical locks already defined for:

```text
table schema
stable MvccRowId
typed unique key
```

Normal physical table and ordered-index access now uses the inherited RawStore record-lock policy:

```text
LockingPolicy.MODE_RECORD
TransactionController.ISOLATION_READ_UNCOMMITTED
```

RawStore still owns container intent, record locking, latching, WAL, undo, commit, and recovery. The
MVCC access method does not introduce another physical lock manager.

Short database-metadata allocation operations retain an inherited serializable container boundary.
That narrow boundary reserves durable database-wide transaction and commit identities; it is not the
normal table-row mutation path.

## Active-writer filtering

READ_UNCOMMITTED physical access is safe only because physical visibility is not MVCC visibility.
The database-scoped active-writer registry records active `MvccTransactionId` values. Readers reject versions and
ordered-index entries created by another active transaction even if RawStore can physically read the
record.

Commit ordering is:

```text
stamp base versions and their shared index entries
write one RawStore commit record
retire active MvccTransactionId
publish committed high-water
```

Retiring the active identity before publishing the new high-water prevents a snapshot at the newly
published sequence from temporarily rejecting the transaction's committed versions.

## Concurrent table-scoped identity allocation

Row and version identifiers are reserved from one database-scoped in-memory allocator per MVCC table.
The transaction records the highest reservation it used and stages that high-water into the table's
RawStore allocator row before commit.

Rollback may leave harmless numeric gaps. Reuse is not allowed within the running database, and the
committed high-water reconstructs the allocator after reopen. This removes the allocator control row
from the normal writer serialization path without making it a second persistence authority.

## transactional shared index mutation

Normal DML no longer copies and republishes the complete index container. The currently published
container participates directly in the parent RawStore transaction:

```text
append entries for the new MVCC version
retain creator/begin/end visibility fields on each entry
stamp new and predecessor entries during precommit
use RawStore savepoint, abort, WAL, and recovery for physical rollback
```

Readers use READ_UNCOMMITTED physical access and reject entries owned by another active transaction.
The authoritative version chain is still re-read and qualified, so the index remains a candidate
narrowing structure rather than row authority. Until Derby B-tree convergence, predicate lookup scans
all candidate entries deliberately; it never assumes that normal append order is key order.

Full private-generation replacement is retained only for compatibility rebuild and history-removing
vacuum. Those maintenance paths rebuild from authoritative versions, switch the control-row pointer,
and drop the replaced container in the same RawStore transaction.

## Reader fallback during maintenance publication

A READ_UNCOMMITTED control-row read may transiently observe a maintenance replacement identifier. If
that replacement is not openable by the reader, lookup returns "not answered" and the scan falls back
to the authoritative stable-row directory and version chain. It never reports false row absence.

## Compatibility control rows

A pre-index table can have a shorter control-row shape. Publication therefore performs a complete control-row rewrite rather than attempting to update a field that does not exist physically. The
rewrite remains logged and transactional.

## Recovery and memory

Normal index-entry insertion and sequence stamping are ordinary logged RawStore record mutations.

```text
crash before RawStore commit:
    base-version and shared-index mutations are undone

crash after RawStore commit before in-memory publication:
    base-version and shared-index mutations recover together
```

A vacuum replacement remains an ordinary RawStore container whose control-row switch and old-container
drop share that same outcome. `jdbc:derby:memory:` uses the same `MODE_RECORD`, active-writer, direct
mutation, and maintenance-replacement implementation. No filesystem fallback or memory-specific
storage authority exists.

## Current limits

The append-only candidate representation removes whole-index rewrite amplification but does not yet add
B-tree descent, page-level key directories, or predicate/range-gap locks. Equality and range probes scan
all candidate entries until the retained Derby B-tree becomes the physical index authority. Vacuum,
maintenance replacement, and recovery remain complete and transactional.

## Executable proof

Focused runtime gate:

```text
:delosdb-tests:runDelosMvccRawStorePhysicalLockingTest
```

Permanent architecture gate:

```text
delosMvccRawStorePhysicalLockingStaticAnalysis
```

The proof covers different-row writer concurrency, nonblocking snapshot reads, transaction-local
index visibility, in-place savepoint/rollback behavior, stable normal-DML container identity,
both RawStore crash boundaries, reopen, and real memory-database operation. Vacuum tests separately
cover atomic maintenance-generation replacement and old-generation retirement.
