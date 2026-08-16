# DelosDB v1 RawStore MVCC mixed heap transactions

## Status

```text
Status: IMPLEMENTED
```

## Decision

A local SQL transaction may mutate inherited heap tables and RawStore-backed
`delos_mvcc` tables together.

Both access methods use the same inherited `RAMTransaction` and its RawStore transaction. There is no
second publication decision for the RawStore-backed MVCC participant.

```text
heap page mutations
    +
RawStore-backed MVCC version/directory mutations
    +
MVCC precommit begin/end/high-water stamping
    +
one RawStore commit record
```

The outcome is always:

```text
all committed
or
all rolled back
```

## Transaction ordering

DML classification occurs before either provider mutates:

```text
DMLWriteResultSet
    -> register heap or MVCC write intent
```

The first RawStore-backed MVCC mutation then:

```text
registers RawStore-owned MVCC participation
reserves the database-wide MvccTransactionId
attaches one MvccRawStoreTransactionContext to RAMTransaction
```

Heap mutations remain normal RawStore operations. MVCC mutations remain normal RawStore operations.
The transaction lifecycle is:

```text
close statement controllers
MVCC lifecycle beforeCommit
    -> reserve one MvccCommitSequence
    -> stamp all pending MVCC versions
    -> stage committed high-water
RawStore commit
MVCC lifecycle afterCommit
    -> publish committed high-water in memory
```

The retired external decision coordinator is not used, and no retained external-format MVCC writer
remains in the production path.

## Rollback and savepoints

RawStore performs physical undo for both providers.

For a transaction rollback:

```text
heap page mutations are undone
MVCC version inserts and field updates are undone
MVCC row-directory head changes are undone
```

For rollback to a savepoint:

```text
RawStore rolls both providers back to one physical savepoint
MvccRawStoreTransactionContext then reconciles its pending logical-version list
```

The MVCC lifecycle participant does not own a second undo stream.

## Crash behavior

The executable proof halts a child JVM at both relevant boundaries.

### Before the RawStore commit record

```text
heap mutations: absent after recovery
MVCC mutations: absent after recovery
committed MVCC high-water: unchanged
```

### After the RawStore commit record but before MVCC in-memory publication

```text
heap mutations: present after recovery
MVCC mutations: present after recovery
committed MVCC high-water: reconstructed from RawStore metadata
```

No mixed state is accepted after recovery.

## Memory databases

The same behavior is exercised through:

```text
jdbc:derby:memory:<name>;create=true
```

Heap pages, MVCC containers, MVCC database metadata, undo, and transaction state all use the inherited
memory-database lifecycle. No separate MVCC memory backend is selected.

## Permanent proof

```text
:delosdb-tests:runDelosMvccRawStoreMixedHeapTransactionTest
delosMvccRawStoreMixedHeapTransactionStaticAnalysis
```

The executable proof covers:

```text
heap-first and MVCC-first mutation order
mixed INSERT/UPDATE/DELETE commit
mixed rollback
cross-provider savepoint rollback
heap-only commit without MVCC counter movement
clean shutdown and reopen
halt before the RawStore commit record
halt after the RawStore commit record
real memory-database commit and rollback
```

## Current limits

This slice does not add:

```text
retained external-format MVCC mixing
XA MVCC writes
nested MVCC update transactions
RawStore-backed MVCC secondary indexes
unique constraints over MVCC versions
vacuum or purge
final fine-grained locking
```

The RawStore-backed MVCC format is now the production authority; the former transitional removal gates
are complete.
