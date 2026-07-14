# Phase 7.2 — MVCC Transaction Durability Protocol Authority

## Purpose

This document is the authoritative description of the current inherited
`delos_mvcc` write-transaction durability route.

It records the behavior that exists before Phase 7 changes force placement,
writer locking, or commit batching. It is deliberately descriptive rather than
aspirational. The current protocol remains unchanged by this slice.

## Required crash invariant

The target transaction contract is:

```text
After commit acknowledgement:
    recovery reconstructs the complete committed transaction.

Before commit acknowledgement:
    recovery may reconstruct the complete transaction or none of it,
    but must never expose a partial committed transaction.
```

The current implementation does not yet have one explicit transaction-level
durable fence that proves the second rule for a process crash in the middle of
a multi-row persistence loop. Phase 7 must establish that fence before removing
or deferring any existing force.

## Current identities

The live path uses two transaction identities:

```text
MVCC transaction-table id
    owned by MvccTransactionManager
    recorded in the .txstatus file
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

The ACTIVE force occurs before JDBC/storage `commit()` is called.

### 2. Commit admission

`MvccInheritedTable.commit()`:

```text
enter process-wide backup durable-mutation guard
acquire the inherited-table write lock
validate all changed rows can be encoded and persisted
```

The current write lock serializes same-table durability execution.

### 3. Transaction-table publication

`MvccTransactionManager.commit()`:

```text
allocate the next commit sequence
force COMMITTED(transaction-table id, commit sequence) into .txstatus
publish the committed outcome in the in-memory transaction catalog
remove the transaction from the active set
```

This currently happens before page-volume persistence.

### 4. Page-volume WAL batch

`PageVolumeMvccWriteAheadLog.appendVersionBatch()` forces one append containing:

```text
BEGIN(page-volume transaction id)
VERSION OPERATION(row 1)
...
VERSION OPERATION(row N)
COMMIT(page-volume transaction id, commit sequence)
```

The WAL provides contiguous page LSNs and a WAL-before-page-flush boundary. It
contains operation type and row id, but not the encoded row payload. The strict
payload recovery path is therefore not able to reconstruct missing row values
from this WAL alone.

### 5. Per-row recovery and page publication

For every changed row, `PageBackedMvccTable.appendCommittedRecord()` currently
performs:

```text
force VERSION(page-volume transaction id, encoded version) into .pagemut
force COMMIT(page-volume transaction id, commit sequence) into .txoutcome
force COMMIT(page-volume transaction id, commit sequence) into .pagemut
append the version to the page volume
rewrite row-directory/free-space/visibility sidecars as required
force dirty page-volume pages
```

The COMMIT records are intentionally idempotent, but they are currently repeated
once per row. An N-row transaction therefore publishes N outcome COMMIT records,
N mutation-log COMMIT records, and performs at least N row-page force cycles.

### 6. Cross-subsystem metadata and checkpoint

After all changed rows have been persisted, the page-volume state store records
its pre-rebuild subsystem snapshot:

```text
append ROW_PAGE recovery record
append INDEX_PAGE recovery record for the currently materialized ordered-index state
append OVERFLOW_PAGE recovery record
append FREE_SPACE_MAP recovery record
append TRANSACTION_OUTCOME recovery record
rewrite checkpoint metadata
append CHECKPOINT recovery record
```

`MvccInheritedTable` then calls `indexMaintenance.rebuildFromCommittedRows()`.
That rewrite appends a second `INDEX_PAGE` recovery record after the checkpoint:

```text
rebuild ordered-index pages from the committed row image
append INDEX_PAGE recovery record for the post-rebuild ordered-index state
```

Both index records currently carry transaction id and commit sequence `0`. They
are lifecycle snapshots rather than transaction-correlated records. The final
`INDEX_PAGE` record is the one describing the ordered-index image produced by
this commit's rebuild. This duplication and ordering are current protocol facts,
not a recommended future durability fence.

### 7. Acknowledgement

The commit call returns only after:

```text
transaction status publication
WAL batch force
all per-row recovery records and page writes
cross-subsystem recovery records
checkpoint rewrite
ordered-index rebuild and post-rebuild INDEX_PAGE recovery record
optional synchronous purge
```

have completed successfully.

## Current authority by responsibility

| Responsibility | Current authority |
|---|---|
| active/terminal transaction-table state | `.txstatus` |
| commit-sequence allocation | `MvccTransactionManager` |
| page LSN ordering and WAL-before-page check | page-volume `.wal` |
| encoded row payload redo | `.pagemut` |
| strict committed/aborted payload outcome | `.txoutcome` |
| materialized row image | page volume plus row-directory sidecars |
| cross-subsystem replay completeness metadata | `.recovery` |
| final ordered-index lifecycle snapshot | the last post-rebuild `INDEX_PAGE` record in `.recovery` |
| durable image summary and lifecycle marker | `.checkpoint` |
| SQL/JDBC acknowledgement | return from `MvccInheritedTable.commit()` |

No one component currently owns all of these as one transaction durability
fence.

## Phase 7.1 measured cost

The target JDK 25 benchmark established:

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

The page-mutation COMMIT/version appends and other sidecar forces are reported
separately from the headline outcome/WAL/page counters.

The benchmark also proved:

```text
same-table durability execution concurrency = 1
different-table/process durability concurrency reaches the writer count
```

Independent tables still scale poorly because force amplification saturates the
durable path before table-lock contention becomes the only limiting factor.

## Unresolved atomicity window

The current sequence has no separate all-rows payload-complete marker between
the per-row `.pagemut`/`.txoutcome` records and commit acknowledgement.

A crash after some rows have completed step 5 but before all rows and subsystem
records have completed leaves a structurally ambiguous image:

```text
.txstatus may already say COMMITTED
.wal may describe the full transaction
.pagemut/.txoutcome may contain committed payload records for only a prefix
page volumes may contain only that same prefix
```

The WAL cannot supply the missing payloads. Existing subsystem metadata is
written after the row loop and is not itself the payload redo authority.

Phase 7.3 must therefore introduce and prove one transaction-complete durable
fence before consolidating forces. The required crash test must interrupt every
stage of a multi-row commit and prove recovery exposes either all rows or none.

## Next implementation boundary

The next slice may change durability behavior only after it defines:

```text
PREPARING
PAYLOAD_REDO_RECORDED
TRANSACTION_OUTCOME_DURABLE
PAGES_STAGED
DURABLE
PUBLISHED
ACKNOWLEDGED
```

These names describe protocol states; they do not require a permanent production
enum.

The intended first optimization remains intra-transaction consolidation:

```text
record every row payload under one page-volume transaction
publish one transaction terminal outcome
force dirty pages once at the transaction fence
acknowledge only after the fence succeeds
```

Cross-transaction group commit and table-lock narrowing remain later work.
