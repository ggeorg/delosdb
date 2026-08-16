# Access-method transaction lifecycle seam

## Status

```text
Status: IMPLEMENTED
Current consumer: RawStore-backed MVCC transaction context
Legacy independent MVCC integration: retired
```

## Purpose

A RawStore-backed access method needs transaction-local semantic state, but it must not own a second
commit, abort, savepoint, XA, or recovery system. The lifecycle seam therefore belongs to the access
transaction which already brackets the real RawStore operation.

The implementation consists of:

```text
AccessMethodTransactionLifecycle
TransactionManager lifecycle registration methods
RAMTransaction identity-keyed lifecycle ownership
```

The seam does not replace RawStore. It lets one access method attach semantic state to one inherited
transaction unit.

## Ownership

`RAMTransaction` owns lifecycle registrations in insertion order.

A registration has:

```text
opaque identity key
AccessMethodTransactionLifecycle instance
```

Keys use object identity rather than `equals()`. A database-owned access-method runtime can therefore
obtain exactly one transaction-local context without a static JVM registry.

A transaction unit clears its lifecycle registrations after:

```text
successful synchronized commit
successful commitNoSync
successful abort
successful XA commit or rollback
transaction destruction
```

A failed commit or abort retains the registrations so inherited abort/context-destruction cleanup can
retire them.

## Commit ordering

Synchronized commit is bracketed as follows:

```text
close non-held controllers
beforeCommit(SYNCHRONIZED)
RawStore commit
RawStore success
alter-table state reset
afterCommit(SYNCHRONIZED)
retire transaction-unit lifecycle registrations
```

`commitNoSync` distinguishes the inherited lock bit even when other flags, such as
`READONLY_TRANSACTION_INITIALIZATION`, are also present:

```text
NO_SYNC_RELEASE_LOCKS
NO_SYNC_KEEP_LOCKS
```

The callback ordering is otherwise identical. RawStore remains responsible for the durability meaning
of synchronized and unsynchronized commit.

A `beforeCommit` failure occurs before RawStore commit. A RawStore commit failure invokes
`commitFailed` and retains lifecycle state for abort or destroy cleanup.

`afterCommit` runs only after RawStore reports success. It may publish in-memory semantic state but may
not perform another durable commit or introduce another transaction decision.

## Abort ordering

```text
beforeAbort
close all controllers
RawStore abort
parent abort for inherited nested-update semantics, when present
RawStore success
afterAbort
retire lifecycle registrations
```

A RawStore abort failure invokes `abortFailed` and leaves lifecycle state available to the inherited
destroy path.

`beforeAbort` and `afterAbort` are intentionally non-throwing contracts. `RAMTransaction` still guards
against unchecked callback failures: every callback is attempted, RawStore abort is never skipped, and
the callback failure is reported only after successful physical undo or attached to a RawStore failure.

## Savepoints

The exact savepoint identity is:

```text
(name, kindOfSavepoint)
```

Callbacks run only after RawStore succeeds:

```text
RawStore setSavePoint
    -> afterSetSavepoint

RawStore rollbackToSavePoint
    -> afterRollbackToSavepoint

RawStore releaseSavePoint
    -> afterReleaseSavepoint
```

This preserves the accepted rule that RawStore performs physical rollback first and semantic state is
trimmed second. Internal statement savepoints with a null kind are included.

## Nested transactions

Before creating a nested user transaction, `RAMTransaction` invokes:

```text
beforeNestedUserTransaction(readOnly)
```

The callback occurs before context or RawStore child creation. A future MVCC lifecycle may therefore:

```text
allow read-only nested transactions
reject nested update transactions before mutation
```

Internal RawStore system transactions are not automatically given the parent access-method lifecycle.
They remain narrowly scoped physical system transactions.

## XA boundary

Before RawStore changes XA state, the lifecycle receives one of:

```text
MORPH_LOCAL_TO_XA
PREPARE
COMMIT_ONE_PHASE
COMMIT_TWO_PHASE
ROLLBACK
```

The initial RawStore-backed MVCC consumer will reject these operations when MVCC semantic state exists.
The seam does not claim MVCC XA support.

## Destruction

Destroy is a forced-cleanup path, not commit publication:

```text
beforeDestroy callbacks
close controllers
RawStore destroy
context cleanup
afterDestroy callbacks
retire all registrations
```

Runtime callback failures are accumulated, all callbacks are attempted, RawStore destruction still
runs, and registration retirement occurs in the final path.

## Held cursors

The seam does not make a completed transaction context own a held cursor after commit.

Before commit, an MVCC participant must transfer any required immutable snapshot lease to the held
cursor. The transaction-unit lifecycle is then retired normally. Closing the cursor releases its own
lease.

## Final RawStore boundary

The transitional external transaction registry has been removed. RawStore-backed MVCC participates
through the access-method transaction lifecycle seam and the inherited RawStore transaction only.
Commit, abort, savepoint, and destroy ordering therefore have one executable authority.

## Verification

The focused executable test covers:

```text
synchronized commit ordering
commitNoSync KEEP_LOCKS and RELEASE_LOCKS, including combined flags
commit failure notification and cleanup retention
abort ordering and retirement
abort callback failure cannot prevent RawStore undo
savepoint identity and RawStore-first callbacks
nested-update rejection before child creation
XA and local-to-XA rejection before RawStore state change
destroy callbacks and final retirement
identity-key registration behavior
```

The permanent static gate is:

```text
delosAccessMethodTransactionLifecycleStaticAnalysis
```
