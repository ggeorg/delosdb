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

## Balanced modernization roadmap closeout

Status: closed green.

This Phase A–J balanced modernization pass is closed. The gates now preserve the completed proof chain across fork governance, MVCC authority cleanup, heap diagnostics, shared inspector consolidation, MVCC cache/overflow/recovery metadata, and heap internal cleanup phase 1. Future work should open a new roadmap instead of extending this closed one.

## Completed execution order

The active plan is no longer the closed storage-robustness closeout plan. The completed
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

The first two items were audit/governance slices. The candidate-index quarantine and
authority-removal slices were MVCC modernization slices. Execution then returned to
heap/inherited-code diagnostics before adding shared services. The final heap cleanup
slice closed the balanced pass.

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

Status: closed green.

After an MVCC modernization slice, return to inherited heap/Derby code. Expand
read-only heap diagnostics without changing heap format, raw log format, catalog
behavior, DRDA behavior, optimizer behavior, or repair semantics.

Diagnostics should cover page counts, allocated/free pages, overflow pages, estimated
storage size, index/table storage size, deleted/non-deleted row summaries, raw-store
sanity summaries, and compress before/after stats where available.

Do not skip the heap slice.

## Phase F — Shared storage inspector consolidation

Status: closed green.

Once heap diagnostics and MVCC diagnostics both exist, consolidate the storage inspector
shape across providers. The common inspection result should expose provider identity,
container/table identity, page summary, index summary, overflow summary, consistency
status, diagnostic findings, and read-only metadata.

This phase is a shared-service consolidation gate, not a new engine feature. Heap
inspection remains database-directory aware and Derby-format compatible. MVCC inspection
remains provider-owned. Mixed reports should present both through one read-only metadata
query/report surface.

Shared inspection contract: provider identity, container/table identity, page summary, index summary, overflow summary, consistency status, diagnostic findings, and read-only metadata.

No repair commands, behavior changes, format changes, SQL syntax, or module merging are
allowed in this slice unless separately gated.

## Phase G — MVCC pinned/dirty buffer cache

Status: closed green.

Add or strengthen real pinned/dirty MVCC page-cache discipline: pin/unpin tracking,
dirty-page tracking, flush-list tracking, bounded eviction respecting pins, deterministic
flush behavior for tests, checksum/generation validation, and reopen correctness.

Required behavior:

```text
pin/unpin tracked
dirty pages tracked
flush-list state tracked
bounded eviction respects pinned and dirty pages
dirty flush order remains deterministic enough for tests
checksum/generation validation remains green
reopen correctness remains green
provider-neutral diagnostics expose cache lifecycle state
```

This phase is MVCC-owned infrastructure. It must not change Derby heap page
format, Derby raw log format, SQL syntax, DRDA behavior, module boundaries, or
inherited heap compatibility paths.

## Phase H — Attribute-level MVCC overflow storage

Status: closed green.

Large values spill at the attribute level using overflow descriptors. Small
attributes remain inline. Multi-attribute rows can mix inline and overflow. Overflow
chains remain page-backed, validated, vacuumable, and inspectable. Heap compatibility
remains unaffected.

Required behavior:

```text
small attributes remain inline
large attributes spill through attribute-level overflow descriptors
multi-row and multi-version tables can mix inline rows with attribute-overflow rows
overflow chains validate missing, extra, wrong-size, or wrong-order chunks
large-value update, delete, vacuum, and reopen paths remain correct
provider-neutral diagnostics expose attribute-overflow counters and byte summaries
MVCC capability metadata reports attribute-overflow support without optimizer consumption
```

This phase is MVCC-owned storage behavior. It must not change Derby heap page format,
Derby raw log format, SQL syntax, DRDA behavior, module boundaries, or inherited heap
compatibility paths.

## Phase I — MVCC subsystem recovery records

Status: closed green.

Make MVCC recovery metadata explicit for row pages, index pages, overflow pages,
free-space map changes, transaction outcomes, and checkpoints. This phase records
subsystem-level recovery boundaries; it does not introduce repair commands, SQL
syntax, DRDA behavior changes, Derby heap page-format changes, or Derby raw-log
format changes.

Required behavior:

```text
row page redo metadata
index page redo metadata
overflow page redo metadata
free-space map redo metadata
transaction outcome redo metadata
checkpoint metadata
monotonic recovery-record sequence validation
provider-neutral recovery diagnostics
reopen preserves recovery metadata records
complete checkpoint boundary is inspectable
```

This phase is MVCC-owned recovery metadata. It must not change Derby heap page
format, Derby raw log format, SQL syntax, DRDA behavior, module boundaries,
optimizer behavior, or inherited heap compatibility paths.

## Phase J — Heap internal cleanup phase 1

Status: closed green.

Only after shared diagnostics and MVCC services mature should inherited heap internals
be cleaned again. Allowed work includes helper extraction, accidental-coupling reduction,
removing dead DelosDB-added branches, tightening assertions, adding diagnostics, and
clarifying page/allocation helper boundaries. Heap page format, raw log format, catalog, DRDA, JDBC, and optimizer behavior remain unchanged in this phase.

Required behavior:

```text
helper extraction stays diagnostic-only or compatibility-preserving
raw-page debug assertion consolidation stays behind SanityManager boundaries
heap/raw-store boundary diagnostics remain read-only
heap page format mutation remains disallowed
raw log format mutation remains disallowed
catalog mutation remains disallowed
existing heap SQL reads and index reads remain green
shutdown and reopen preserve heap compatibility
no SQL syntax, DRDA, JDBC, optimizer, catalog, module, page-format, or log-format changes
```

This phase is a heap/inherited-code cleanup gate. It records and tests service-boundary
clarity around OpenHeap, HeapController, OpenConglomerate, BasePage, StoredPage,
FileContainer, AllocPage, D_StoredPage, D_HeapController, diagnostic formatting helpers,
and raw-page debug assertion helpers without changing Derby-compatible behavior.


## Post-closeout tradeoff audit

Status: active audit.

The Phase A-J pass closed the balanced modernization gates, but the first follow-up
audit found two implementation tradeoffs that should be fixed before opening the
next large roadmap:

```text
path/storage-id hardening must reject a single Windows backslash and control characters
optional MVCC sidecar stores must disable cleanly on null or blank storage ids before path resolution
Phase G remains a write-through pinned/dirty cache boundary, not a deferred dirty-page policy
Phase I remains recovery-record metadata, not a redo executor
```

The first two items are concrete hardening fixes in this overlay. The latter two are
kept as explicit tradeoffs so future plans do not over-claim the current state. A
later cache/recovery implementation phase may replace write-through flushing or add
redo execution, but this post-closeout audit does not change SQL behavior, page
format, raw-log format, catalog behavior, DRDA behavior, optimizer behavior, or
module boundaries.

## Decision rules

Work on MVCC when a normal SQL authority still depends on temporary or diagnostic
structures.

Work on heap/inherited code when the default Derby-compatible path lacks diagnostics,
hardening, cleanup gates, or clear compatibility boundaries.

Work on shared services only after heap and MVCC have comparable proof points and a
common diagnostic/inspection/report shape prevents duplication.

Work on fork governance when inherited Derby files become high-risk extension seams and
need explicit classification before deeper edits.

## Final closeout state

Status: closed green.

All Phase A–J roadmap gates are now closed green. The next DelosDB storage roadmap
should start from a fresh plan and preserve the standing execution rules above rather
than reopening this completed pass.
