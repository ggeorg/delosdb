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
stamp versions and rebuild private index
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

## transaction-private ordered-index generation

A writer does not rewrite the currently published ordered-index container. On its first indexed
mutation for a table, it:

```text
creates a private RawStore container
rebuilds the visible ordered state into that container
applies statement-time INSERT or UPDATE entries there
keeps the published generation available to other transactions
```

At precommit, while holding the database publication boundary, the transaction rebuilds the private
generation from authoritative base versions, rewrites the table control row to point to it, and
transactionally drops the replaced generation. The control-row switch, old-container drop, base
version stamps, allocator high-water, and committed high-water share the parent RawStore commit.

Savepoint rollback reconciles transaction-local generation state against RawStore container existence.
A generation created after the savepoint disappears through RawStore undo and is removed from the
transaction context.

## Reader fallback during publication

A READ_UNCOMMITTED control-row read may transiently observe a writer's not-yet-published container
identifier. If that private container is not openable by the reader, the ordered-index lookup returns
"not answered" and the scan falls back to the authoritative stable-row directory and version chain.
It never reports false row absence.

## Compatibility control rows

A pre-index table can have a shorter control-row shape. Publication therefore performs a complete control-row rewrite rather than attempting to update a field that does not exist physically. The
rewrite remains logged and transactional.

## Recovery and memory

The private generation is an ordinary RawStore container.

```text
crash before RawStore commit:
    private generation, control-row switch, and row changes are undone

crash after RawStore commit before in-memory publication:
    new generation and row changes recover together
```

`jdbc:derby:memory:` uses the same `MODE_RECORD`, active-writer, private-generation, and publication
implementation. No filesystem fallback or memory-specific storage authority exists.

## Current limits

This milestone does not add predicate/range-gap locks, finalize page-layout performance, or remove the
retained Phase 8 storage system. Transactional vacuum and purge are implemented by the next Stage 4
slice in `V1-RAWSTORE-MVCC-VACUUM.md`; background maintenance, relocation, and incremental ordered-index
page maintenance remain later work.

## Executable proof

Focused runtime gate:

```text
:delosdb-tests:runDelosMvccRawStorePhysicalLockingTest
```

Permanent architecture gate:

```text
delosMvccRawStorePhysicalLockingStaticAnalysis
```

The proof covers different-row writer concurrency, nonblocking snapshot reads, transaction-private
index visibility, savepoint rollback, atomic generation replacement, old-generation retirement,
both RawStore crash boundaries, reopen, and real memory-database operation.
