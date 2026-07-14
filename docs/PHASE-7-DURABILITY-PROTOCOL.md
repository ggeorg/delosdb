# Phase 7 — MVCC Transaction Durability Protocol

## Purpose

This document is the authoritative description of the inherited `delos_mvcc`
write-transaction durability route after the Phase 7.6 immutable prepared-commit
boundary.

The prepared payload batch and transaction outcome remain the recovery
authority. Main-table page images are now staged as one transaction batch and
forced once. Checkpointing, ordered-index rebuilding, transaction locking, and
public behavior remain unchanged.

## Required crash invariant

```text
After commit acknowledgement:
    recovery reconstructs the complete committed transaction.

Before commit acknowledgement:
    recovery may reconstruct the complete transaction or none of it,
    but must never expose a committed prefix.
```

The page-mutation and transaction-outcome logs now prove this invariant for a
multi-row transaction.

## Transaction identities

The live path still uses two transaction identities:

```text
MVCC transaction-table id
    owned by MvccTransactionManager
    recorded in .txstatus
    exposed in the commit JFR event

page-volume transaction id
    owned by PageVolumeMvccStateStore
    recorded in .wal, .pagemut, .txoutcome, and subsystem recovery records
```

They are separate allocation domains. The shared durable correlation is the
positive commit sequence, not identity equality.

## Current write-transaction sequence

### 1. Begin

`MvccTransactionManager.begin()`:

```text
allocate MVCC transaction-table id
force ACTIVE into <storage>.txstatus
register the active transaction in memory
return the transaction handle
```

### 2. Immutable preparation and commit admission

`MvccInheritedTable.commit()` first captures the transaction's surviving write
intents and revision under a short table read lock. It then deep-copies logical
rows, encodes all non-delete payloads, and validates page-record limits outside
the table write lock.

The immutable prepared commit then enters:

```text
backup durable-mutation guard
per-table durability coordinator
inherited-table write lock
revalidate transaction activity, revision, and same-row ownership
```

Multiple non-conflicting same-table commits may prepare concurrently. Physical
same-table durability execution remains serialized by the per-table coordinator.

### 3. Transaction-table publication

`MvccTransactionManager.commit()`:

```text
allocate the next commit sequence
force COMMITTED(transaction-table id, commit sequence) into .txstatus
publish the committed outcome in the in-memory transaction catalog
remove the transaction from the active set
```

This still occurs before page-volume persistence. Moving this publication is not
part of Phase 7.4.

### 4. Page-volume WAL batch

`PageVolumeMvccWriteAheadLog.appendVersionBatch()` forces one append containing:

```text
BEGIN(page-volume transaction id)
VERSION OPERATION(row 1)
...
VERSION OPERATION(row N)
COMMIT(page-volume transaction id, commit sequence)
```

The WAL supplies page LSNs and the WAL-before-page boundary. It does not contain
encoded row payloads.

### 5. Prepared payload batch

`MvccPageMutationLog.appendPreparedTransaction()` forces one append containing:

```text
BEGIN(page-volume transaction id, commit sequence, expected row count)
VERSION(page-volume transaction id, encoded version 1)
...
VERSION(page-volume transaction id, encoded version N)
PREPARED(page-volume transaction id, commit sequence, expected row count)
```

`PREPARED` proves that the complete expected payload set reached the mutation
log. It is not the commit authority.

The batch is additive to the existing log format. Legacy VERSION/COMMIT records
remain readable.

### 6. Transaction-complete outcome fence

After the prepared payload batch succeeds,
`MvccTransactionOutcomeLog.appendCommit()` forces exactly one record:

```text
COMMIT(page-volume transaction id, commit sequence)
```

This outcome record is the page-volume transaction-complete fence.

Recovery applies a prepared transaction only when:

```text
the BEGIN and PREPARED metadata agree
the actual VERSION count equals the expected count
the outcome log contains COMMIT for the same transaction
the outcome commit sequence matches the prepared commit sequence
```

Recovery behavior is:

```text
prepared batch without outcome
    ignore the whole transaction as pre-fence and uncommitted

torn prepared batch without outcome
    ignore the whole transaction

committed outcome plus complete prepared batch
    replay every version idempotently

committed outcome plus incomplete or mismatched prepared batch
    reject recovery before applying a prefix

aborted outcome
    suppress the complete transaction
```

Legacy standalone VERSION records keep their existing strict behavior: an
unknown outcome remains an error. The pre-fence suppression rule applies only
to the new BEGIN/PREPARED transaction format.

Strict recovery also validates every page record already present in the page
volume against the outcome log. A page version from an unknown or aborted
transaction is rejected rather than silently exposed.

### 7. Transaction-level page materialization

After the outcome fence, all prepared versions enter one page-store batch. Row
payload bytes were already encoded in the immutable commit preparation phase:

```text
stage every prepared changed version
update free-space metadata for each touched page
write every dirty main-table page image
force the main-table page volume once
publish row-directory and visibility sidecars
```

The page cache retains dirty state until every page write and the single force
boundary succeed. If a write or force fails after the outcome fence, the code
reports a committed-transaction materialization failure and does not append a
contradictory WAL ABORT. Recovery uses the prepared payload batch and outcome
fence to complete all missing versions idempotently.

Overflow payload volumes retain their existing independent force boundaries;
this slice consolidates the main-table page volume measured by the ordinary
one-row and eight-row benchmark workloads.

### 8. Cross-subsystem metadata and checkpoint

After all changed rows are materialized:

```text
append ROW_PAGE recovery record
append INDEX_PAGE recovery record for the pre-rebuild index state
append OVERFLOW_PAGE recovery record
append FREE_SPACE_MAP recovery record
append TRANSACTION_OUTCOME recovery record
rewrite checkpoint metadata
append CHECKPOINT recovery record
rebuild ordered-index pages
append INDEX_PAGE recovery record for the post-rebuild index state
```

The final `INDEX_PAGE` record is the lifecycle snapshot for the rebuilt ordered
index. Both index records currently use transaction id and commit sequence `0`.

### 9. Acknowledgement

The commit call returns only after:

```text
transaction status publication
WAL batch force
prepared payload batch force
transaction outcome fence force
one main-table page materialization force plus any required overflow forces
cross-subsystem records
checkpoint rewrite
ordered-index rebuild
optional synchronous purge
```

## Current authority by responsibility

| Responsibility | Current authority |
|---|---|
| active/terminal transaction-table state | `.txstatus` |
| commit-sequence allocation | `MvccTransactionManager` |
| page LSN ordering and WAL-before-page check | page-volume `.wal` |
| complete encoded payload batch | `.pagemut` BEGIN/VERSION/PREPARED transaction |
| page-volume transaction-complete fence | one `.txoutcome` COMMIT |
| materialized row image | page volume plus rebuildable row-directory sidecars |
| cross-subsystem replay metadata | `.recovery` |
| durable image summary | `.checkpoint` |
| SQL/JDBC acknowledgement | return from `MvccInheritedTable.commit()` |

## Measured and current force shape

The Phase 7.1 target-machine benchmark measured the old protocol as:

```text
one-row transaction:
    2 transaction-status forces
    1 transaction-outcome force
    1 WAL force
    2 page-volume forces

eight-row transaction:
    2 transaction-status forces
    8 transaction-outcome forces
    1 WAL force
    9 page-volume forces
```

After Phase 7.4 the expected headline shape for inline row payloads is:

```text
one-row transaction:
    2 transaction-status forces
    1 transaction-outcome force
    1 WAL force
    2 page-volume forces

eight-row transaction:
    2 transaction-status forces
    1 transaction-outcome force
    1 WAL force
    2 page-volume forces
```

One page-volume force covers every dirty main-table page in the transaction; the
second is the existing ordered-index materialization force. The mutation payload
log remains one forced transaction batch.

## Compatibility

The mutation log format is extended, not replaced:

```text
legacy VERSION / COMMIT / ABORT / FSYNC
new BEGIN / VERSION... / PREPARED
```

Checkpoint rewrites continue to use the legacy compact committed-image format,
and strict recovery supports both forms.

## Remaining work

Phase 7.4 establishes one main-table page force per transaction. It does not yet
solve:

```text
per-row row-directory and free-space sidecar rewrites
overflow-volume force consolidation
table-wide write-lock scope
cross-transaction group commit
database-level maintenance scheduling
transaction-table COMMITTED publication before the page-volume fence
```

The next decision must come from the rerun benchmark. If independent-table
throughput remains durability-bound, sidecar/status forcing should be measured
before lock-scope work. If the main force reduction removes that bottleneck, the
next implementation slice can narrow same-table write-lock scope.
