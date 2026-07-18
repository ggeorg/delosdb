# Design proof 1: MVCC commit ordering and snapshot mathematics

## Status

```text
local transactions: approved design proof
commitNoSync:        same logical publication rule; durability mode remains RawStore-owned
XA prepare/commit:   unresolved; MVCC participation must remain fail-closed
```

This proof defines correctness semantics. It does not freeze the Java callback API.

## Problem

A commit sequence cannot be treated as visible merely because it was reserved.

The following schedule is invalid if snapshot visibility uses only numeric comparison:

```text
A reserves 10 and stalls before commit
B reserves 11 and commits
snapshot S opens at 11
A later commits with 10
```

If S later accepts every version with `beginSequence <= 11`, A becomes visible to a snapshot opened
before A committed. Snapshot stability is violated.

## Definitions

For one database:

```text
P  = published committed high-water sequence
CS = commit sequence reserved for one finalizing transaction
S  = snapshot sequence captured at snapshot creation
```

A local transaction has these semantic states:

```text
ACTIVE
FINALIZING
COMMITTED
ABORTED
```

A version has:

```text
creator transaction identity
begin commit sequence, absent while uncommitted
ending transaction identity, when being ended
end commit sequence, absent while current or uncommitted
```

Logical MVCC transaction and version identities are stable DelosDB identities. RawStore internal
`XactId` values are not persisted as those identities.

## Approved local-transaction rule

One database-scoped commit-publication boundary serializes local MVCC commit finalization and
snapshot capture.

Conceptually:

```text
commitPublicationLock
publishedHighWater = P
```

### Commit protocol

For a local transaction containing MVCC work:

```text
1. acquire commitPublicationLock
2. verify the transaction may commit
3. reserve CS where CS > P
4. within the user RawStore transaction:
       stamp created versions with begin = CS
       stamp ended versions with end = CS
       stamp transactional derived-index journal records with CS
       update RawStore-owned committed high-water metadata to CS
5. perform the normal RawStore commit
6. only after successful RawStore commit:
       publish in-memory P = CS
7. release commitPublicationLock
```

If any operation before or during RawStore commit fails:

```text
RawStore undo/abort removes the stamped changes
P is not advanced
the reserved sequence may remain an unused gap
```

Commit sequence density is not a correctness requirement.

### Snapshot protocol

```text
1. acquire commitPublicationLock
2. capture S = P
3. register the snapshot/horizon state
4. release commitPublicationLock
```

A snapshot cannot open between sequence reservation and commit publication.

Therefore, every `CS <= S` belongs to a transaction whose RawStore commit completed before the
snapshot captured S.

## Visibility rule

Ignoring the current transaction's own uncommitted writes, a committed version is visible to
snapshot S when:

```text
beginSequence <= S
and
(endSequence is absent or endSequence > S)
```

The current transaction sees its own writes using its `MvccTransactionId` and statement/savepoint
state, not by pretending those writes already have a committed sequence.

A transaction never sees another transaction's uncommitted versions.

## Proof of snapshot stability

Assume snapshot S captures published high-water P while holding the commit-publication lock.

For any transaction T not committed when S is captured:

1. T cannot have published its commit sequence, because publication happens only after RawStore
   commit succeeds while the same lock is held.
2. T cannot complete commit while S captures P, because T requires the same lock.
3. When T later commits, it receives or publishes a sequence greater than the captured P.
4. Therefore T's committed versions have `beginSequence > S` and remain invisible to S.

The invalid schedule where a later commit publishes sequence 10 below snapshot 11 is impossible.

## Crash matrix

### Crash before RawStore commit record

```text
stamped page and metadata changes are uncommitted
RawStore recovery undoes them
persisted high-water does not advance
```

### Crash after RawStore commit record but before in-memory publication

```text
RawStore recovery retains stamped versions and committed high-water metadata
on boot, P is reconstructed from RawStore-owned committed metadata
```

### Crash after publication

```text
committed RawStore state and in-memory publication agree
reopen reconstructs the same P
```

The persistent source of truth is RawStore-owned committed metadata. The in-memory high-water is a
boot-scoped acceleration and coordination value.

## Savepoints and statement rollback

Physical version/index mutations occur during statement execution and are already within the user
RawStore transaction.

```text
statement/savepoint rollback:
    RawStore undoes physical changes to its boundary
    MVCC transaction context trims semantic lists and command state
```

No commit sequence is assigned during ordinary statement execution or savepoint rollback.

## `commitNoSync`

`commitNoSync` does not change logical visibility ordering.

It follows the same commit-publication boundary and publishes only after RawStore reports successful
commit. The requested synchronization/durability mode remains a RawStore concern.

## Read-only and heap-only transactions

A read-only transaction captures a snapshot when MVCC semantics require one.

A heap-only transaction does not need an MVCC commit sequence unless it also writes a transactional
derived-index journal whose ordering must share the database commit sequence. The exact policy is
part of the transaction-lifecycle proof.

## XA boundary

The local protocol cannot simply be applied to an XA prepared transaction:

```text
prepare may be followed by an arbitrarily delayed commit
holding commitPublicationLock across prepare is unacceptable
mutating/stamping user pages after prepare may violate RawStore XA rules
```

Therefore:

```text
MVCC participation in XA remains unsupported and must fail before partial work
```

until a separate proof defines one of:

```text
active/prepared-set snapshots with durable status semantics
or
an XA-safe RawStore commit-order publication mechanism
```

No generic transaction participant API may claim XA support before that proof exists.

## Required tests

```text
A stalls during finalization; snapshot cannot pass publication boundary
A aborts after reserving a sequence; snapshot high-water does not advance
A commits; snapshots opened before and after retain stable results
two concurrent committers publish in commit order
commit failure rolls back stamped version and journal metadata
crash before commit record
crash after commit record before in-memory publication
reopen reconstructs committed high-water
commitNoSync preserves logical ordering
savepoint rollback removes versions from finalization lists
statement rollback cannot receive a commit sequence
XA MVCC work fails closed before mutation
```

## Provisional implementation questions

This proof intentionally does not decide:

```text
lock class and package
exact high-water metadata container/record
sequence allocation mechanism
transaction callback interface
whether non-MVCC derived-index-only transactions share this sequence
how runtime diagnostics expose waiting/finalizing transactions
```

Those choices must preserve the mathematics above.
