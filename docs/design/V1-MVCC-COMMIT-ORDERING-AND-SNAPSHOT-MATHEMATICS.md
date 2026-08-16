# MVCC commit ordering and snapshot mathematics

## Status

```text
local transactions: approved ordered-publication protocol
commitNoSync:        same logical publication rule; durability mode remains RawStore-owned
XA prepare/commit:   unresolved; MVCC participation remains fail-closed
```

This design defines correctness semantics. It does not freeze the Java callback API.

## Problem

A commit sequence cannot become visible merely because it was reserved or because its RawStore
commit completed. With concurrent physical commits, completion may be out of sequence:

```text
A reserves 10 and stalls
B reserves 11 and physically commits
snapshot S opens
A later physically commits
```

If S captured 11 before sequence 10 reached a terminal state, A could later become visible to a
snapshot opened before A completed. Snapshot stability would be violated.

## Definitions

For one database:

```text
P   = contiguous published high-water visible to new snapshots
CS  = commit sequence assigned to one finalizing transaction
NPS = next sequence required before P may advance
RC  = durable recovery publication ceiling
S   = snapshot sequence captured at snapshot creation
```

A commit sequence becomes terminal when its user RawStore transaction either commits or is abandoned.
Sequence density is not required: aborted transactions and unused durable reservations create safe
gaps.

## Approved local-transaction rule

Physical commit work may run concurrently. Visibility publication remains ordered.

### Commit-sequence reservation

The runtime durably reserves commit sequences in blocks of 64. Reservation advances both the durable
next-unreserved sequence and the recovery publication ceiling before any value from the block is
returned.

```text
reserve block 65..128
RC = 128
```

Unused values are never reused after reboot.

### Commit protocol

For a local transaction containing MVCC writes:

```text
1. briefly acquire the publication lock
2. consume CS from the current durable block
3. release the publication lock
4. inside the user RawStore transaction:
       stamp created versions with begin = CS
       stamp ended versions with end = CS
       publish transaction-private ordered-index replacements
       stage allocator high-waters
5. perform the normal RawStore commit without holding the publication lock
6. after RawStore reports successful commit:
       mark CS terminal under the publication lock
       advance P only through the contiguous terminal prefix
7. if CS is ahead of P, wait on the publication condition
8. return commit only after P >= CS
```

A failed/aborted finalization marks its reserved sequence terminal without creating durable user
versions, allowing publication to advance through the gap.

The commit-return barrier is required. Once JDBC `commit()` returns, every subsequently opened
snapshot must be able to observe that commit. A transaction may therefore finish its RawStore commit
before an earlier sequence, but it cannot return to the caller until ordered publication catches up.

### Snapshot protocol

```text
1. acquire the publication lock
2. capture S = P
3. register the snapshot/horizon lease
4. release the publication lock
```

`READ COMMITTED` may capture a new snapshot per statement. `REPEATABLE READ` retains its transaction
snapshot according to the isolation contract.

## Visibility rule

Ignoring the current transaction's own uncommitted writes, a committed version is visible to snapshot
S when:

```text
beginSequence <= S
and
(endSequence is absent or endSequence > S)
```

The current transaction sees its own writes by transaction identity and statement/savepoint state.
A transaction never sees another transaction's uncommitted versions.

## Proof of snapshot stability

Assume snapshot S captures P.

For any sequence greater than P, at least one sequence at or before it has not yet reached the
contiguous terminal prefix. Therefore no later physical commit can advance P past that gap. A snapshot
opened while A(10) is unfinished cannot capture 11 merely because B(11) physically committed first.
When 10 later commits or aborts, publication may advance through 10 and already-terminal 11, but the
older snapshot retains its previously captured S.

Therefore an out-of-order physical commit cannot become visible retroactively to an existing
snapshot.

## Why disjoint writers may physically commit concurrently

The publication lock is not held across version stamping, index publication work, or RawStore commit.
Independent writers may therefore perform the expensive physical finalization work concurrently.
Only sequence allocation, terminal-set mutation, snapshot capture, and publication-frontier movement
use the short database-scoped publication boundary.

## Crash and recovery

The durable recovery publication ceiling RC is intentionally different from the live in-memory P.
During a running process, only the contiguous terminal frontier controls visibility.

### Crash before user RawStore commit

```text
CS may already belong to a durably reserved block
RawStore recovery removes uncommitted stamped user changes
RC remains advanced
no durable version exists for the abandoned CS
```

### Crash after user RawStore commit but before live publication

```text
RawStore recovery retains the committed stamped versions
RC already covers CS
on reopen, the runtime initializes P from the durable reserved ceiling
```

### Why recovery may publish reserved gaps

After RawStore recovery, every durable stamped version belongs to a transaction whose RawStore commit
survived. Unused reserved sequences and aborted transactions have no surviving version rows. It is
therefore safe for the reopened runtime to initialize publication through RC: gaps contain nothing to
expose.

This avoids a second transaction-status or durability authority. RawStore remains the sole physical
commit/recovery authority.

## Savepoints and statement rollback

Physical version/index mutations remain inside the user RawStore transaction.

```text
statement/savepoint rollback:
    RawStore undoes physical changes to its boundary
    MVCC transaction context trims semantic lists and command state
```

Commit sequences are assigned only during transaction finalization.

## `commitNoSync`

`commitNoSync` follows the same ordered visibility-publication rule. The requested synchronization
mode remains a RawStore durability concern.

## Read-only and heap-only transactions

Read-only transactions capture snapshots when MVCC semantics require one. Heap-only transactions do
not need an MVCC commit sequence unless a future shared transactional structure explicitly requires
the same ordering domain.

## XA boundary

An XA prepared transaction cannot use the local protocol unchanged because prepare may be followed by
an arbitrarily delayed commit and user pages cannot be restamped after prepare without a separate
XA-safe publication design.

Therefore MVCC participation in XA remains unsupported and must fail before partial work until an
XA-safe publication protocol exists.

## Required tests

```text
durable block reservation never reuses gaps after reboot
crash before RawStore commit leaves no user row
crash after RawStore commit before publication recovers the row
out-of-order/disjoint writer completion does not create false conflicts
commit return implies visibility to a new snapshot
REPEATABLE READ snapshot remains stable across concurrent disjoint commits
READ COMMITTED may observe later commits on a later statement
aborted reserved sequences do not block publication
multi-table and mixed heap/MVCC transactions retain one RawStore outcome
savepoint and rollback semantics remain unchanged
reopen reconstructs publication from the durable recovery ceiling
XA MVCC work fails closed before mutation
```

## Measured evidence

The focused eight-client disjoint UPDATE x1 JFR checkpoint showed:

```text
serialized block-64 publication:        16,199 tx/s, 0 retries
concurrent publication without return barrier:
                                       22,661 tx/s, 42,328 false retries (rejected)
ordered commit-return publication:     23,773 tx/s, 0 retries
```

The accepted ordered-return design is therefore both the correctness-preserving protocol and the
measured performance direction for v1.
