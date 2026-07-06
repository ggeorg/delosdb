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

## Cycle 0 — Baseline before more engine work

Cycle 0 is audit and governance only. It must not change Java behavior.

```text
1. Derby fork-diff classification
2. MVCC candidate-index authority audit
3. Heap/raw-store inherited-code audit
```

### Derby fork-diff classification

Status: closed green.

High-risk inherited Derby diffs must be classified before deeper engine work. The gate
is intentionally narrow: every known high-risk inherited Derby file from the current
comparison must have a classification row, and the row must describe the surface,
reason, and next action.

Allowed classifications:

```text
COMPATIBILITY_PRESERVING
EXTENSION_SEAM
STORAGE_SPLIT
HARDENING
INTENTIONAL_REPLACEMENT
```

The first fork-diff slice classifies the current high-risk files without modifying Java
behavior:

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

### MVCC candidate-index authority audit

Status: current Cycle 0 audit slice.

Every remaining MVCC candidate-index path must be classified before candidate-index
quarantine, removal, or cleanup work continues. This is an audit/reporting gate only:
it must not remove code, rename APIs, change read authority, or change Java behavior.

Allowed classifications:

```text
NORMAL_SQL_AUTHORITY
EXPLICIT_FALLBACK
DIAGNOSTIC_PARITY
TEST_ONLY
LEGACY_COMPATIBILITY
STALE_CANDIDATE
```

The gate scans the storage API, MVCC implementation, MVCC Derby bridge, Derby heap
provider diagnostics, and DelosDB MVCC SQL tests for active candidate-index authority
mentions. Each active source path must have a single classification row. Stale rows,
duplicate rows, and unclassified active paths fail S0.

A NORMAL_SQL_AUTHORITY row is a quarantine target. In the current tree, remaining
production candidate-index mentions are expected to be diagnostic/parity,
explicit-fallback, legacy-compatibility, or stale diagnostic naming rather than normal
SQL read authority.

## Cycle 1 — First balanced execution cycle

```text
4. MVCC candidate-index quarantine
5. Heap diagnostics expansion
6. Shared storage inspector consolidation
```

Cycle 1 may start only after all Cycle 0 audits are green. Do not skip the heap slice.

## Cycle 2 — Authority removal plus heap boundary cleanup

```text
7. MVCC candidate-index authority removal
8. Heap/raw-store cleanup gate phase 2
9. Cross-engine consistency/reporting expansion
```

## Cycle 3 — MVCC cache plus heap internal cleanup

```text
10. MVCC pinned/dirty buffer cache
11. Heap internal cleanup phase 1
12. Shared page/cache/allocation abstraction audit
```

## Cycle 4 — Overflow and large-value handling

```text
13. MVCC attribute-level overflow storage
14. Heap overflow/long-row diagnostics
15. Shared overflow/large-value inspection
```

## Cycle 5 — Recovery and checkpoint boundaries

```text
16. MVCC subsystem recovery records
17. Heap recovery/logging boundary gate
18. Shared recovery/checkpoint metadata model
```

## Decision rules

Work on MVCC when a normal SQL authority still depends on temporary or diagnostic
structures.

Work on heap/inherited code when the default Derby-compatible path lacks diagnostics,
hardening, cleanup gates, or clear compatibility boundaries.

Work on shared services only after heap and MVCC have comparable proof points and a
common diagnostic/inspection/report shape prevents duplication.

Work on fork governance when inherited Derby files become high-risk extension seams and
need explicit classification before deeper edits.
