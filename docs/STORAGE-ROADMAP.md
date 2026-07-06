# DelosDB Storage Roadmap

This roadmap starts the next balanced modernization pass after the storage robustness,
compatibility, cleanup, and compromise-audit work closed green.

## North star

```text
Preserve Derby compatibility.
Do not preserve Derby internals for their own sake.
```

DelosDB remains a Derby-compatible database engine, not a Derby-identical codebase.
Old-compatible SQL, JDBC, DRDA, catalog, heap, and durable-format behavior must keep
running. Internals may evolve behind explicit gates when compatibility is protected.

## Architecture model

```text
DelosDB : Derby
like
PostgreSQL : Ingres
or
AMD64 : 8086
```

Compatibility is the contract. Derby implementation details are not automatically the
contract. Module and product-boundary parity should still be preserved unless evidence
shows that DelosDB created an artificial split that Derby itself did not have.

## Completed before this roadmap

The previous storage robustness / compatibility plan is closed. The current green
baseline includes:

```text
Derby heap sanity checker through SYSCS_CHECK_TABLE
MVCC isolation checkpoint
Heap FormatIdInputStream object deserialization hardening
Leaf ObjectInputStream hardening for client/server/import/replication sites
Cross-engine consistency framework
Runtime artifact / provider discovery model
Module dependency report and boundary static gate
Temporary overlay cleanup script closeout
Workspace churn static gate
Derby module parity gate
Cleanup/consolidation phase
Final compromise audit and hardening iteration
```

These are guarded by focused tests and S0 static gates. Do not reopen or weaken them
without a specific regression or compatibility gap.

## Standing execution rules

```text
Never do two major MVCC implementation phases in a row without a heap/inherited-code phase between them.
Never do heap modernization without a compatibility gate.
Never create a shared service until both heap and MVCC have concrete proof points.
Never merge modules just because they look small.
Never preserve Derby internals unless they are part of compatibility.
```

## Updated near-term execution order

The active plan is no longer the closed storage-robustness closeout plan. The next
execution order is:

```text
1. Derby fork-diff classification
2. MVCC candidate-index authority audit
3. MVCC candidate-index quarantine
4. MVCC candidate-index authority removal
5. Heap diagnostics expansion
6. Shared storage inspector consolidation
7. MVCC pinned/dirty buffer cache
8. MVCC attribute-level overflow storage
9. MVCC subsystem recovery records
10. Heap internal cleanup phase 1
```

The first two items are audit/governance slices. The candidate-index quarantine and
authority-removal slices are MVCC modernization slices. After them, execution must
return to heap/inherited-code diagnostics before adding shared services.

## Phase A — Baseline and fork-diff classification

Status: closed green.

Before touching more inherited Derby code, DelosDB needs a maintained map of where it
intentionally diverges from Derby. High-risk inherited Derby diffs are classified as:

```text
COMPATIBILITY_PRESERVING
EXTENSION_SEAM
STORAGE_SPLIT
HARDENING
INTENTIONAL_REPLACEMENT
```

The gate classifies these high-risk files without Java behavior changes:

```text
GenericResultSetFactory.java
FromBaseTable.java
RAMAccessManager.java
DataDictionaryImpl.java
SYSTABLESRowFactory.java
TableDescriptor.java
CreateTableNode.java
CreateIndexNode.java
OpenHeap.java
FormatIdInputStream.java
NetworkServerControlImpl.java
DRDAConnThread.java
```

## Phase B — MVCC candidate-index authority audit

Status: closed green.

Every remaining MVCC candidate-index path is classified before quarantine, removal, or
cleanup work continues. This is an audit/reporting gate only: it must not remove code,
rename APIs, change read authority, or change Java behavior.

Allowed classifications:

```text
NORMAL_SQL_AUTHORITY
EXPLICIT_FALLBACK
DIAGNOSTIC_PARITY
TEST_ONLY
LEGACY_COMPATIBILITY
STALE_CANDIDATE
```

A `NORMAL_SQL_AUTHORITY` row is a quarantine target. In the current green tree, the
audit is expected to have no active `NORMAL_SQL_AUTHORITY` rows; remaining production
candidate-index mentions are diagnostics, explicit fallback accounting, legacy
compatibility, or stale vocabulary.

## Phase C — MVCC candidate-index quarantine

Status: closed green.

Candidate indexes must no longer be silently used as normal authority on covered
current-committed paths. They may remain only as:

```text
explicit fallback accounting
diagnostic parity source
test comparison source
emergency compatibility seam
```

Required behavior:

```text
normal equality reads prefer ordered pages
normal range reads prefer ordered pages where supported
candidate fallback counter exists
candidate fallback counter is zero on covered paths
explicit diagnostic mode can compare candidate vs ordered
candidate indexes are not removed yet
snapshot reads do not use unsafe shortcut
writer-borrowed reads do not use unsafe shortcut
```

The static gate for this phase validates that the authority-audit file has no
`NORMAL_SQL_AUTHORITY` rows, the focused SQL quarantine test is wired into the MVCC SQL
integration task, ordered-index authority markers exist at the scan boundary, and the
legacy diagnostic fallback property remains hard-quarantined.

## Phase D — MVCC candidate-index authority removal

Status: closed green.

Only after quarantine is green should candidate-index authority removal be treated as a
separate closeout. Candidate structures may remain temporarily for diagnostics,
migration comparison, or explicit fallback, but normal SQL reads should use ordered MVCC pages.

Required behavior:

```text
ordered pages are normal SQL index authority
candidate authority removed or hard-quarantined
fallback is explicit, counted, and gated
unique behavior unchanged
updates/deletes/reopen correct
read-your-writes still correct
snapshot semantics unchanged
```

The static gate for this phase validates that candidate-index fallback is not SQL
authority, the legacy diagnostic property cannot re-enable it, covered equality/range
reads use ordered index pages, ordered row ids feed page-backed committed reads,
reopen keeps the removal contract, and capability/statistics metadata report the
authority-removal fact. Candidate structures may still be populated for parity
diagnostics until the later diagnostic-renaming cleanup.

## Phase E — Heap diagnostics expansion

Status: current executable heap/inherited-code slice after green candidate-index authority removal.

After an MVCC modernization slice, return to inherited heap/Derby code. Expand
read-only heap diagnostics without changing heap format, raw log format, catalog
behavior, DRDA behavior, optimizer behavior, or repair semantics.

Diagnostics should cover page counts, allocated/free pages, overflow pages, estimated
storage size, index/table storage size, deleted/non-deleted row summaries, raw-store
sanity summaries, and compress before/after stats where available.

Do not skip the heap slice.

## Phase F — Shared storage inspector consolidation

Status: next shared-service slice after heap diagnostics expansion is green.

Once heap diagnostics and MVCC diagnostics both exist, consolidate the storage inspector
shape across providers. The common inspection result should expose provider identity,
container/table identity, page summary, index summary, overflow summary, consistency
status, diagnostic findings, and read-only metadata.

No repair commands, behavior changes, format changes, SQL syntax, or module merging are
allowed in this slice unless separately gated.

## Phase G — MVCC pinned/dirty buffer cache

Status: later MVCC infrastructure slice.

Add or strengthen real pinned/dirty MVCC page-cache discipline: pin/unpin tracking,
dirty-page tracking, flush-list tracking, bounded eviction respecting pins, deterministic
flush behavior for tests, checksum/generation validation, and reopen correctness.

## Phase H — Attribute-level MVCC overflow storage

Status: later MVCC storage slice.

Large values should spill at the attribute level using overflow descriptors. Small
attributes remain inline. Multi-attribute rows can mix inline and overflow. Overflow
chunks must be reusable and inspectable. Heap compatibility remains unaffected.

## Phase I — MVCC subsystem recovery records

Status: later MVCC recovery slice.

Make MVCC recovery metadata explicit for row pages, index pages, overflow pages,
free-space map changes, transaction outcomes, and checkpoints.

## Phase J — Heap internal cleanup phase 1

Status: later heap/inherited-code cleanup slice.

Only after shared diagnostics and MVCC services mature should inherited heap internals
be cleaned again. Allowed work includes helper extraction, accidental-coupling reduction,
removing dead DelosDB-added branches, tightening assertions, adding diagnostics, and
clarifying page/allocation helper boundaries. Heap page format, raw log format, catalog,
DRDA, JDBC, and optimizer behavior are not allowed to change in this phase.

## Decision rules

Work on MVCC when a normal SQL authority still depends on temporary or diagnostic
structures.

Work on heap/inherited code when the default Derby-compatible path lacks diagnostics,
hardening, cleanup gates, or clear compatibility boundaries.

Work on shared services only after heap and MVCC have comparable proof points and a
common diagnostic/inspection/report shape prevents duplication.

Work on fork governance when inherited Derby files become high-risk extension seams and
need explicit classification before deeper edits.
