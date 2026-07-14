# Phase 7 — MVCC Transaction Durability Protocol

## Purpose

This document is the authoritative description of the inherited `delos_mvcc`
write-transaction durability route after the Phase 7.3 transaction-complete
fence.

The fence changes payload and outcome logging only. Page materialization,
checkpointing, ordered-index rebuilding, transaction locking, and page-force
placement remain unchanged.

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

### 2. Commit admission

`MvccInheritedTable.commit()`:

```text
enter the backup durable-mutation guard
acquire the inherited-table write lock
validate all changed rows can be encoded and persisted
```

The table write lock still serializes same-table durability execution.

### 3. Transaction-table publication

`MvccTransactionManager.commit()`:

```text
allocate the next commit sequence
force COMMITTED(transaction-table id, commit sequence) into .txstatus
publish the committed outcome in the in-memory transaction catalog
remove the transaction from the active set
```

This still occurs before page-volume persistence. Moving this publication is not
part of Phase 7.3.

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

### 7. Page materialization

After the outcome fence, each prepared version is materialized through the
existing page path:

```text
append one version to the page volume
rewrite row-directory/free-space/visibility state as required
force dirty page-volume pages
```

This path still forces per changed row. If materialization fails after the
outcome fence, the code reports a committed-transaction materialization failure
and does not append a contradictory WAL ABORT. Recovery can complete the
remaining versions from the prepared payload batch.

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
all current page materialization and page forces
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

After Phase 7.3 the expected headline shape is:

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
    9 page-volume forces
```

The mutation payload log also moves from repeated VERSION/COMMIT forced appends
to one forced transaction batch. Page-volume forces are deliberately unchanged.

## Compatibility

The mutation log format is extended, not replaced:

```text
legacy VERSION / COMMIT / ABORT / FSYNC
new BEGIN / VERSION... / PREPARED
```

Checkpoint rewrites continue to use the legacy compact committed-image format,
and strict recovery supports both forms.

## Remaining work

Phase 7.3 establishes the transaction-complete fence and removes repeated
payload/outcome log forcing. It does not yet solve:

```text
per-row page-volume forces
per-row row-directory/free-space/visibility rewrites
table-wide write-lock scope
cross-transaction group commit
database-level maintenance scheduling
transaction-table COMMITTED publication before the page-volume fence
```

The next implementation slice should batch page materialization inside one
page-mutation context and force the page volume once per transaction. It must
reuse the outcome fence and rerun the same crash matrix before changing the
table lock or adding group commit.
