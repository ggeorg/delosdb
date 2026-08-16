# Design proof 2: complete Derby transaction lifecycle matrix

## Status

```text
local commit paths:          accepted
local rollback paths:        accepted
statement/savepoint paths:   accepted
held-cursor ownership:       accepted requirement
nested read transaction:     accepted read-only rule
nested update transaction:   fail-closed for MVCC
XA participation:            fail-closed for MVCC
database/session teardown:   accepted cleanup rule
exact Java callback API:     implemented
```

This proof defines the lifecycle semantics preserved by the neutral access-method transaction seam.
The implemented seam lives in `RAMTransaction` and is consumed by the RawStore-backed MVCC transaction
context.

## Source seams inspected

The inherited Derby lifecycle is distributed across several real code paths rather than one generic
commit callback:

```text
org.apache.derby.impl.sql.conn.GenericLanguageConnectionContext
org.apache.derby.impl.sql.conn.GenericStatementContext
org.apache.derby.impl.store.access.RAMTransaction
org.apache.derby.impl.store.access.RAMTransactionContext
org.apache.derby.impl.store.raw.xact.Xact
org.apache.derby.iapi.store.raw.Transaction
org.apache.derby.iapi.store.access.TransactionController
org.apache.derby.iapi.store.access.XATransactionController
```

Important inherited facts:

1. `RAMTransaction.commit()`, `commitNoSync()`, `abort()`, savepoint methods, `destroy()`, and XA
   methods delegate separately to the RawStore transaction.
2. `GenericLanguageConnectionContext.doCommit()` chooses normal commit, `commitNoSync`, or XA commit.
3. `GenericStatementContext.cleanupOnError()` implements statement rollback through an internal
   savepoint whose `kindOfSavepoint` is `null`.
4. transaction-severity errors invoke full rollback through the language connection context.
5. session/database-severity cleanup may destroy contexts without returning through a normal user
   commit or rollback call.
6. `RAMTransaction.commit()` closes only non-held controllers; held controllers may survive commit.
7. savepoint identity is the pair `(name, kindOfSavepoint)`, not the name alone.
8. RawStore commit failures may be converted into transaction-severity aborts, while more severe
   failures are completed by context cleanup.

A correct MVCC integration therefore cannot be attached only to JDBC or language-level commit calls.
It must be owned by the access transaction lifecycle and remain safe when cleanup is driven by error
contexts.

## Semantic state owned by one access transaction

The RawStore-backed MVCC path attaches transaction-local semantic state to one
`RAMTransaction`-equivalent owner:

```text
MvccTransactionId, when MVCC writes first occur
transaction snapshot, when required
created-version references
ended-version references
derived-index journal references
savepoint semantic markers
state: ACTIVE / FINALIZING / COMMITTED / ABORTING / ABORTED / DESTROYED
```

The physical mutations are already RawStore mutations. The semantic state only records the values
needed for visibility finalization, own-write visibility, savepoint trimming, diagnostics, and
cleanup.

An open held cursor may own a separate snapshot lease. That lease is not discarded merely because
the transaction commits.

## Ordering rules

### Local commit

For a local transaction with MVCC writes:

```text
1. close or prepare non-held access controllers according to inherited commit behavior
2. acquire the database commit-publication boundary
3. mark the MVCC context FINALIZING
4. reserve and stamp the commit sequence under the accepted commit-publication protocol
5. call the RawStore commit operation
6. after RawStore reports success, publish the committed high-water
7. retire transaction-owned MVCC semantic state
8. release the publication boundary
```

If RawStore commit fails, no committed high-water is published. The context enters abort/error cleanup
and must be retired idempotently after RawStore abort or context destruction.

### Local abort

```text
1. mark the MVCC context ABORTING
2. close transaction-owned controllers and non-held snapshot leases
3. invoke RawStore abort
4. after successful abort, discard transaction-local semantic state
5. if abort itself fails severely, context destruction still performs idempotent retirement
```

No commit sequence is reserved or published.

### Savepoint rollback

RawStore remains the physical undo authority:

```text
1. invoke RawStore rollback to the exact (name, kindOfSavepoint) identity
2. only after RawStore rollback succeeds, trim MVCC semantic lists and command state
3. retain the named savepoint; remove semantic markers created after it
```

If RawStore rollback fails at transaction severity, the full transaction abort path owns cleanup.

### Savepoint release

```text
1. invoke RawStore release for the exact (name, kindOfSavepoint) identity
2. after success, remove the named semantic marker and all later markers
```

This matches RawStore stack semantics.

## Lifecycle matrix

| Derby path | RawStore outcome | MVCC semantic action | Commit sequence | Snapshot/held-cursor action | Accepted v1 convergence rule |
| --- | --- | --- | --- | --- | --- |
| `userCommit()` / synchronized local commit | `commit()` ends the current unit | finalize before RawStore commit; retire after success | reserve and publish under the commit-publication protocol | transaction snapshot ends; held cursor lease may survive | supported |
| `internalCommit(true)` | same physical commit, internally requested | same as local commit | same as local commit | same ownership rule | supported |
| `internalCommit(false)` | no RawStore commit | do not finalize or retire MVCC transaction state | none | current transaction snapshot remains | non-terminal; no lifecycle callback may treat it as commit |
| `internalCommitNoSync(RELEASE_LOCKS)` | logical commit without requested log sync | same logical finalization as commit | reserve/publish under the commit-publication protocol after RawStore success | transaction snapshot ends; held cursor lease may survive | supported; durability remains RawStore-owned |
| `internalCommitNoSync(KEEP_LOCKS)` | logical unit commits while locks may be retained | retire old MVCC transaction unit after success; future writes require a new semantic unit | reserve/publish if MVCC writes existed | held controllers and their leases may survive independently | supported only with explicit transaction-unit reset |
| normal `abort()` / user rollback | RawStore undoes full unit | mark aborting, then discard after abort | none | close transaction-owned snapshots/controllers | supported |
| statement-severity error | RawStore rollback to internal savepoint | trim semantic state after RawStore rollback | none | statement resources close; transaction snapshot continues | supported |
| user/JDBC savepoint create | RawStore pushes savepoint | record marker after RawStore success | none | no snapshot change | supported; identity includes kind object |
| rollback to savepoint | RawStore undoes to marker and retains target | trim lists after physical rollback | none | refresh-style rollback may close controllers | supported |
| release savepoint | RawStore removes target and newer savepoints | remove corresponding semantic markers after success | none | no snapshot change | supported |
| read-only nested user transaction | separate read-only RawStore transaction | independent read-only MVCC snapshot context if needed | none | child lease closes/destroys independently | supported |
| update nested user transaction | separate child RawStore update unit; child abort may abort parent | no MVCC mutation permitted during initial convergence | none | no MVCC child snapshot with writes | fail closed before MVCC mutation |
| nested top/internal transaction used for metadata allocation | independent physical system transaction | may allocate durable counters only; may not publish table versions | allocation gaps allowed; no visible commit publication | no user snapshot | narrowly permitted for proven metadata allocation only |
| local transaction morphed to XA | allowed only while inherited transaction is idle | MVCC context must be absent | none | no MVCC snapshot/write state | fail closed if MVCC state exists |
| `xa_prepare()` read-only vote | RawStore aborts/returns `XA_RDONLY` | no MVCC context may exist | none | no MVCC state | fail closed before any MVCC mutation |
| `xa_prepare()` update vote | RawStore enters prepared state | unsupported for MVCC | none | unsupported | fail closed before any MVCC mutation |
| XA one-phase commit | RawStore commits global transaction | unsupported for MVCC | none | unsupported | fail closed before any MVCC mutation |
| XA two-phase commit | RawStore commits only from prepared state | unsupported for MVCC | none | unsupported | fail closed before any MVCC mutation |
| XA rollback | RawStore aborts global transaction | no MVCC context should exist; cleanup remains idempotent | none | no MVCC state | fail-closed boundary retained |
| transaction-severity failure | language context invokes full rollback; RawStore/context owns abort | retire after abort; cleanup must tolerate duplicate calls | none | close transaction-owned leases | supported |
| session-severity or unexpected JVM error | contexts are popped/destroyed; RawStore destroy aborts if active | no finalization; idempotent forced retirement | none | close all database/session-owned leases | supported cleanup path |
| `RAMTransaction.destroy()` / explicit context destruction | RawStore `destroy()` aborts if non-idle, then closes | retire in `finally`; never publish | none | close all transaction-owned leases | supported cleanup path |
| connection close | rollback/destroy according to inherited connection state | same as abort/destroy | none | held cursors close with connection | supported |
| database shutdown | active transactions are aborted/destroyed before database-owned runtime closes | close all remaining contexts and leases; no static registry | none | all database-owned leases close | supported |
| recovery boot | RawStore completes redo/undo before normal access | reconstruct published high-water before snapshots open | no new sequence during reconstruction | reject snapshot acquisition until reconstruction completes | supported boot rule |

## Held cursors and snapshot ownership

The inherited access transaction closes non-held controllers on commit but allows held controllers to
remain open. Consequently:

```text
transaction context
    owns transaction-wide snapshot and write semantics

held MVCC cursor
    owns a separate immutable snapshot lease needed to continue fetching after commit
```

After commit:

1. the transaction-local MVCC context is retired;
2. a held cursor may continue using only its cursor-owned lease;
3. that lease cannot accept new writes or become the next transaction's snapshot;
4. closing the held cursor releases the lease and its vacuum-horizon protection.

This requirement prevents commit from either invalidating a legal held cursor or retaining an entire
completed transaction context indefinitely.

## Error and idempotency requirements

Lifecycle cleanup can be entered through normal calls, transaction-severity exceptions, session
cleanup, or `destroy()`. Therefore:

```text
abort cleanup is idempotent
destroy cleanup is idempotent
snapshot lease close is idempotent
semantic-context retirement is idempotent
commit publication is not idempotently repeatable and occurs only after confirmed RawStore commit
```

A callback that runs only from `GenericLanguageConnectionContext` is insufficient. A callback that
runs only after `rawtran.commit()` is also insufficient because commit may throw after RawStore has
already forced abort semantics.

The eventual access-method seam must bracket the RawStore operation and have an error/termination
path owned by the access transaction context.

## Interface constraints derived from the matrix

`AccessMethodTransactionLifecycle` implements the neutral lifecycle seam. The interface distinguishes:

```text
local synchronized commit
local commitNoSync plus KEEP_LOCKS/RELEASE_LOCKS
non-terminal internalCommit(false)
full abort
statement/internal savepoint rollback
user/JDBC savepoint identity
savepoint release
transaction/context destroy
read-only nested transaction
update nested transaction rejection
XA fail-closed conversion/prepare/commit/rollback
held-cursor snapshot lease transfer
```

A five-method interface containing only `beforeCommit`, `beforeRollback`, and name-only savepoint
methods is insufficient.

## Required implementation tests

Before the transaction seam is considered complete, tests must cover:

```text
normal commit with and without MVCC writes
commit failure before and during RawStore commit
commitNoSync with RELEASE_LOCKS
commitNoSync with KEEP_LOCKS and subsequent transaction work
internalCommit(false) does not finalize MVCC state
normal rollback
statement-level rollback through internal savepoint
SQL and JDBC savepoints with distinct kind identities
rollback and release semantics for nested savepoints
held MVCC cursor fetch after commit
held cursor close releases vacuum horizon
read-only nested transaction isolation
MVCC write rejection in nested update transaction
local-to-XA conversion rejection after MVCC state exists
all XA mutation paths fail before MVCC mutation
transaction-severity cleanup
session-severity cleanup
destroy of active and idle transactions
database shutdown with active readers/writers
recovery reconstructs publication state before snapshot acquisition
```

## Decision

The transaction-lifecycle design is accepted with these boundaries:

```text
local transaction lifecycle:     supported by the current access-method seam
statement/savepoint lifecycle:   supported with RawStore-first physical ordering
held cursor lifecycle:           separate cursor-owned snapshot lease required
nested read-only transaction:    supported
nested update MVCC:              fail closed
XA MVCC:                         fail closed
exact Java lifecycle seam:       implemented
```

The RawStore MVCC page/container design builds on this lifecycle seam.


## Implementation record

The implemented seam is documented in:

```text
docs/design/V1-ACCESS-METHOD-TRANSACTION-LIFECYCLE-SEAM.md
```

`RAMTransaction` brackets the inherited RawStore commit, commitNoSync, abort, savepoint,
nested-user-transaction, XA, and destroy paths. RawStore-backed MVCC uses that lifecycle seam directly;
the transitional external transaction registry no longer exists.
