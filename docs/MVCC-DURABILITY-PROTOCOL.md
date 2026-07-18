# MVCC transaction durability protocol

## Purpose

This document is the authoritative description of the current `delos_mvcc`
write-transaction durability route. Historical implementation slices are
retained under `docs/history/`; they do not override this protocol.

The protocol separates three responsibilities that must not be confused:

```text
prepared payload durability
    proves that the complete encoded transaction can be recovered

database transaction-status publication
    publishes the durable COMMITTED decision and commit sequence

page-volume publication
    mirrors the decision locally, materializes pages and sidecars, and advances
    recovery/checkpoint metadata
```

## Required invariants

```text
Before database COMMITTED is durable:
    recovery exposes none of the staged transaction.

After database COMMITTED is durable:
    recovery reconstructs the complete committed transaction.

At every crash boundary:
    recovery must never expose a committed prefix.

After database COMMITTED is durable:
    no code path may append a contradictory ABORT for that transaction.

If live materialized state may differ from durable committed authority:
    the table becomes recovery-required and serves no further operations.
```

## Database-wide raw-store authority

Provider payload durability and the provider transaction-status journal remain the MVCC recovery
authority described below. The enclosing Derby raw-store transaction is nevertheless the
**database transaction authority** whenever a local transaction contains an MVCC write or an MVCC
conglomerate lifecycle action.

```text
heap/catalog/DDL work only
    Derby raw store follows its inherited commit path

any local MVCC DML
    prepare every MVCC participant
    log one database commit decision in the Derby raw transaction
    commit the raw store synchronously
    publish the prepared MVCC participants after that decision

MVCC CREATE or DROP
    log the surviving lifecycle action in the same Derby raw transaction
    publish CREATE or physically retire DROP only after raw-store commit
```

This rule intentionally does not depend on detecting a heap DML writer. Catalog descriptors,
conglomerate metadata, index DDL, and other raw-store mutations can therefore never commit
independently of MVCC data in the same local transaction. No-sync and XA paths reject before
mutation when this authority cannot be provided; MVCC DDL in XA is unsupported.

## Transaction identities

The inherited path has two independent transaction-id domains:

```text
MVCC transaction-table id
    owned by MvccTransactionManager
    recorded in <storage>.txstatus
    used for ACTIVE / COMMITTED / ABORTED state

page-volume transaction id
    owned by PageVolumeMvccStateStore
    recorded in .wal, .pagemut, .txoutcome, and .recovery
```

The ids must never be assumed equal. New prepared page-mutation batches record
the MVCC transaction-table id explicitly so recovery can correlate a missing
local outcome record with the correct database transaction status.

Commit sequence remains the ordered visibility identity shared by both domains.

## Current write-transaction sequence

### 1. Begin

`MvccTransactionManager.begin()`:

```text
allocate MVCC transaction-table id
force ACTIVE into <storage>.txstatus
register the active transaction in memory
return the transaction handle
```

Read-only transactions do not create durable status records.

### 2. Immutable logical preparation

`MvccInheritedTable` delegates commit execution to
`MvccInheritedCommitLifecycle`. The lifecycle captures the transaction's write
intents and revision under the table read lock. It then:

```text
deep-copies logical row values
encodes non-delete payloads
validates page-record limits
creates one immutable MvccPreparedCommit
```

This work occurs before ordered durability publication. Non-conflicting writers
may prepare concurrently.

### 3. Ordered validation and commit-sequence preparation

The prepared commit enters:

```text
database backup durable-mutation guard
bounded per-table commit coordinator
inherited-table write lock
```

The group is revalidated for:

```text
active transaction state
unchanged write-intent revision
same-row conflicts
provider write ownership
```

`MvccTransactionManager.prepareCommitBatch()` then assigns ordered commit
sequences without:

```text
writing COMMITTED
advancing newestCommitSequence
removing active transactions
publishing in-memory committed outcomes
```

A staged member may fail before status publication. Its unused sequence may
therefore become a harmless gap.

### 4. Durable pre-commit payload staging

For every surviving member, `PageVolumeMvccStateStore.stagePreparedChanges()`
allocates a page-volume transaction id and forces the recoverable payload before
the shared COMMITTED status append.

#### 4.1 WAL batch

`PageVolumeMvccWriteAheadLog.appendVersionBatch()` forces one append containing:

```text
BEGIN(page transaction id)
VERSION OPERATION(row 1)
...
VERSION OPERATION(row N)
COMMIT(page transaction id, commit sequence)
```

The WAL record supplies page LSN ordering. Its COMMIT marker closes the WAL
batch; it is not the database transaction commit decision.

#### 4.2 Prepared page-mutation batch

`MvccPageMutationLog.appendPreparedTransaction()` forces one append containing:

```text
BEGIN(page transaction id,
      commit sequence,
      expected record count,
      correlated transaction-table id)
VERSION(page transaction id, encoded version 1)
...
VERSION(page transaction id, encoded version N)
PREPARED(page transaction id,
         commit sequence,
         expected record count,
         correlated transaction-table id)
```

`PREPARED` proves that the complete expected payload set reached durable storage.
It is not a terminal outcome.

If staging fails before database COMMITTED:

```text
append local ABORT when possible
append WAL ABORT when possible
abort the active MVCC transaction
return failure only for that member
```

Even if an ABORT append is itself interrupted, a complete prepared batch with no
terminal committed authority remains invisible during recovery.

### 5. Shared database COMMITTED publication

After every surviving payload is staged,
`MvccTransactionManager.publishPreparedCommitBatch()` performs one forced
transaction-status append for the group:

```text
COMMITTED(transaction-table id 1, commit sequence 1)
...
COMMITTED(transaction-table id N, commit sequence N)
```

Only after that append succeeds does the in-memory transaction manager:

```text
advance newestCommitSequence
remove the transactions from the active set
publish retained committed outcomes
```

This transaction-status append is the database commit decision.

The status-force call is treated as an ambiguity boundary:

```text
failure known before the force
    staged transactions may be aborted

failure from the status publication call
    durable outcome may be unknown
    do not append ABORT
    mark the table recovery-required

failure after the status call returned
    transactions are known committed
    do not append ABORT
    mark the table recovery-required
```

### 6. Local outcome mirror and page materialization

For each committed member, `publishStagedChanges()` performs:

```text
append .txoutcome COMMIT(page transaction id, commit sequence)
materialize every prepared version
write and force the complete dirty main-page batch
publish row-directory and visibility sidecars
```

The local `.txoutcome` record is the ordinary page-recovery authority. If its
append was interrupted after database COMMITTED, strict recovery uses the
explicit transaction-id correlation in `.pagemut` and the durable `.txstatus`
COMMITTED record to supply the missing local outcome.

Recovery accepts that fallback only for the new explicitly correlated
BEGIN/PREPARED format. It never infers equality between the two transaction-id
domains and never applies the fallback to legacy records.

Recovery behavior is:

```text
complete prepared batch with no local outcome and no correlated COMMITTED status
    ignore the whole transaction

correlated COMMITTED status plus complete prepared batch
    replay every version idempotently

COMMITTED authority plus incomplete or mismatched prepared batch
    reject recovery before applying a prefix

local or correlated ABORTED outcome
    suppress the complete transaction

legacy VERSION-only record with unknown outcome
    retain the existing strict corruption rule
```

### 7. Cross-subsystem publication and checkpoint

After page materialization:

```text
append row-page recovery metadata
append index-page recovery metadata
append overflow-page recovery metadata
append free-space-map recovery metadata
append transaction-outcome recovery metadata
rewrite checkpoint metadata
append checkpoint recovery metadata
```

A failure in this section does not make the transaction uncommitted. The durable
status and prepared payload remain sufficient to recover. The live table becomes
recovery-required and must be reopened before serving more work.

### 8. Ordered-index publication

After all group members have materialized successfully, the ordered index is
rebuilt once for the group.

If index publication fails:

```text
committed transactions remain committed
the table becomes recovery-required
all affected commit callers receive an explicit committed/recovery-required error
no subsequent read or write is served from the stale live index
reopen rebuilds the index from committed rows
```

### 9. Post-commit maintenance

Purge scheduling or synchronous post-commit maintenance occurs only after the
transaction and ordered index are published.

A maintenance `RuntimeException`:

```text
does not change commit success
does not append ABORT
does not poison the table
increments failure diagnostics and stores the latest failure summary
```

Maintenance is not part of the transaction commit decision.

### 10. Acknowledgement

A normal successful commit returns only after:

```text
complete payload staging
shared database COMMITTED status force
local outcome publication
page and sidecar materialization
cross-subsystem recovery metadata
checkpoint publication
ordered-index publication
post-commit maintenance attempt
```

A failure after database COMMITTED returns an exception whose contract states
that the transaction is committed or its status is indeterminate and must not
be retried blindly. The table simultaneously enters recovery-required state.

## Transactional MVCC conglomerate lifecycle

### CREATE

The provider must create files before the statement can use the new conglomerate, but those files
are not committed authority. Statement execution first writes and forces:

```text
delos_mvcc/ddl-lifecycle/create-<segment>-<container>.pending
```

The lifecycle action is owned by `DelosStorageTransactionRegistry`, including savepoint depth. Only
action instances which survive to final commit preparation are written into the Derby raw log.

```text
rollback or rollback-to-savepoint
    close and remove the live provider state
    delete all staged conglomerate files
    remove pending/committed lifecycle markers

raw-store commit
    live completion publishes CREATE after raw commit; raw recovery does so after interruption
    normal live completion clears the marker

process halt before a durable raw decision
    reopen sees a lone pending marker and retires the orphan files

process halt after a durable raw decision
    raw-log recovery publishes CREATE before provider state is opened
```

A transient committed marker is written before the pending marker is removed. It therefore protects
the provider files across every marker-transition crash window; startup preserves files when that
marker exists and then clears both markers.

### DROP

`MvccConglomerate.drop()` no longer deletes durable state during statement execution. It registers a
transaction-owned DROP action and leaves the live table and files intact while the raw transaction
is abortable.

```text
rollback or rollback-to-savepoint
    discard the DROP action; provider state remains unchanged

raw-store commit
    live completion deletes the conglomerate files after participant publication; raw recovery does so after interruption
    live completion removes and closes the runtime table state

process halt before a durable raw decision
    no provider files were deleted; normal raw recovery preserves the table

process halt after a durable raw decision
    raw-log recovery completes physical retirement
```

Dropping the final MVCC conglomerate also retires the now-empty database transaction-status journal,
preserving the bounded-retention contract.

The raw lifecycle operations are undoable and have stable registered format IDs. Their logging is
deferred until final commit preparation so Derby rollback-to-savepoint cannot leave post-commit work
for an action which no longer belongs to the transaction.

## Live fail-stop state

`MvccInheritedTable` records the first recovery-required failure atomically.
Once set, ordinary operations fail with `TableRecoveryRequiredException`.

This protects against serving:

```text
partially materialized pages
stale row-directory or visibility sidecars
stale ordered-index authority
an in-memory transaction catalog that may differ from durable status
```

Closing and reopening the table executes strict recovery and rebuilds the
rebuildable authorities.

## Current authority by responsibility

| Responsibility | Durable authority |
|---|---|
| Active/terminal transaction state | `<storage>.txstatus` |
| Ordered commit decision | grouped `.txstatus` COMMITTED records |
| Commit-sequence allocation | `MvccTransactionManager` plus recovered `.txstatus` |
| WAL-before-page ordering | page-volume `.wal` |
| Complete encoded payload | `.pagemut` BEGIN/VERSION/PREPARED batch |
| Page-local outcome mirror | `.txoutcome` |
| Missing local outcome after committed status | explicit `.pagemut` status-id correlation plus `.txstatus` |
| Materialized row image | page volume plus rebuildable sidecars |
| Cross-subsystem replay metadata | `.recovery` |
| Durable image summary | `.checkpoint` |
| Ordered lookup authority | ordered-index pages after successful rebuild |

Runtime ownership is separated from durable authority: `MvccInheritedTable` is
the provider facade, `MvccInheritedCommitLifecycle` owns commit/recovery
orchestration, and `MvccInheritedMaintenanceLifecycle` owns purge and vacuum
scheduling.

## Force shape

For ordinary inline payloads, one transaction currently performs:

```text
begin:
    1 ACTIVE transaction-status force

commit:
    1 shared COMMITTED status force per group
    1 WAL payload-batch force per transaction
    1 prepared mutation-batch force per transaction
    1 local transaction-outcome force per transaction
    1 main-page-volume force per transaction
    rebuildable sidecar/checkpoint forces required by current stores
    1 ordered-index publication force per group when rows changed
```

The protocol intentionally does not yet share WAL, local outcome, or page-volume
forces across transactions.

## Compatibility

The mutation-log format is additive:

```text
legacy VERSION / COMMIT / ABORT / FSYNC
older BEGIN / VERSION... / PREPARED without status correlation
new BEGIN / VERSION... / PREPARED with explicit status transaction id
```

Strict recovery supports all forms. Database-status fallback is used only when
the prepared batch explicitly records the correlated transaction-table id.

Checkpoint rewrites may continue to emit the compact legacy committed-image
format.

## Failure-proof requirements

Any future durability change must retain focused tests for:

```text
failure before shared status publication
ambiguous shared status publication
failure immediately after shared status publication
failure before local outcome append
partial page materialization
subsystem recovery-record failure
checkpoint failure
ordered-index publication failure
post-commit maintenance failure
reopen idempotence after every committed failure
no ABORT record after durable COMMITTED
```

No force may be removed unless a crash/reopen proof demonstrates the replacement
protocol.

## Remaining work outside this protocol

```text
sharing additional WAL/outcome/page fences across transactions
narrowing same-table physical publication locking
full serializability
mature database-wide buffer and I/O orchestration
checkpoint-generation plus WAL-tail backup
broader external process-crash and storage-fault injection
operational tooling and field hardening
```
