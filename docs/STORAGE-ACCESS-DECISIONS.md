# DelosDB storage access decision audit

This document defines the first shared vocabulary for DelosDB storage access-path decisions.
It is an audit artifact, not a behavior change.

## Scope

DelosDB now has several storage-access choices spread across inherited Derby heap paths, the DelosDB MVCC bridge, ordered MVCC index pages, row-id reads, predicate pushdown diagnostics, candidate-index parity diagnostics, and compatibility fallbacks.

The purpose of this slice is to make those choices nameable before they become a runtime diagnostic stream.

This slice does not:

* Do not replace Derby's optimizer.
* change heap scan or B-tree scan behavior,
* change MVCC visibility rules,
* resurrect candidate indexes as SQL authority,
* change ordered-index fallback behavior,
* change plan selection,
* add Calcite, HerdDB, or MapDB as dependencies.

## Decision vocabulary

The typed vocabulary lives in:

```text
org.apache.derby.iapi.store.types.DelosStorageAccessDecisionKind
```

Current decision kinds:

* `FULL_HEAP_SCAN` — inherited Derby-compatible heap table scan.
* `HEAP_INDEX_SCAN` — inherited Derby-compatible heap/B-tree index scan.
* `MVCC_FULL_SCAN` — MVCC full scan where row/version visibility is the authority.
* `MVCC_ORDERED_EQUALITY_LOOKUP` — current-committed equality lookup through ordered MVCC index pages.
* `MVCC_ORDERED_RANGE_SCAN` — current-committed range lookup through ordered MVCC index pages.
* `MVCC_ROW_ID_LOOKUP` — MVCC row-id point read or row-id narrowed read.
* `MVCC_VISIBILITY_FILTERED_INDEX_LOOKUP` — an index-narrowed MVCC read that must still apply visibility checks.
* `DIAGNOSTIC_CANDIDATE_PARITY_SCAN` — candidate-index diagnostic/parity path only.
* `EXPLICIT_COMPATIBILITY_FALLBACK` — deliberate fallback from an unsafe shortcut to an authority path.
* `TEST_ONLY_PATH` — proof/test/diagnostic-only path.

## Current audit

### FULL_HEAP_SCAN

Current owner: inherited Derby heap access.

Source seam:

```text
org.apache.derby.impl.store.access.heap.Heap#openScan
```

Compatibility rule: this is a Derby compatibility anchor.  Do not reroute or reinterpret heap scans without a heap compatibility gate.

### HEAP_INDEX_SCAN

Current owner: inherited Derby B-tree/index access.

Source seam:

```text
org.apache.derby.impl.store.access.btree.index.B2I#openScan
```

Compatibility rule: this is a Derby compatibility anchor.  DelosDB diagnostics may observe it, but must not replace it without a compatibility proof.

### MVCC_FULL_SCAN

Current owner: DelosDB MVCC bridge.

Source seam:

```text
io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccInheritedTable#openScan
```

Algorithm rule: full MVCC scans delegate row/version correctness to page-backed visibility, statement snapshots, transaction-local write intents, and historical snapshot reads.

### MVCC_ORDERED_EQUALITY_LOOKUP

Current owner: ordered MVCC index pages reached through the bridge.

Source seams:

```text
MvccConglomerateState#orderedIndexRowIdsFor
MvccInheritedIndexMaintenance#orderedIndexRowIdsFor
```

Algorithm rule: ordered equality lookup is current-committed row-id narrowing.  It is not safe for transaction-scoped snapshots or borrowed writer reads unless the visibility algorithm explicitly permits it.

### MVCC_ORDERED_RANGE_SCAN

Current owner: ordered MVCC index pages reached through the bridge.

Source seams:

```text
MvccConglomerateState#orderedIndexRowIdsFor
MvccInheritedIndexMaintenance#orderedIndexRowIdsInRangeFor
```

Algorithm rule: ordered range lookup narrows row ids, then row qualification and visibility still decide the returned row.

### MVCC_ROW_ID_LOOKUP

Current owner: DelosDB MVCC bridge and committed-image read surface.

Source seams:

```text
MvccScanController#readCurrentCommittedOrSnapshot
MvccConglomerateController#fetch
MvccInheritedTable#readCommittedImage
```

Algorithm rule: row-id lookup is a fast access path, not a bypass around visibility.  Snapshot or write-intent cases must continue through the MVCC table read path.

### MVCC_VISIBILITY_FILTERED_INDEX_LOOKUP

Current owner: MVCC scan controller.

Source seam:

```text
MvccScanController#advanceToNextIndexedRow
```

Algorithm rule: index output is only a row-id candidate list.  The row still needs current-committed or snapshot visibility, plus Derby qualifier evaluation.

### DIAGNOSTIC_CANDIDATE_PARITY_SCAN

Current owner: candidate-index diagnostics retained for parity and checker work.

Source seams:

```text
DelosStorageCandidateIndex#candidateRowIdsFor
MvccInheritedIndexMaintenance#orderedIndexCandidateParityErrors
```

Algorithm rule: Candidate indexes are retained for diagnostics and parity checks, not as normal SQL read authority.

### EXPLICIT_COMPATIBILITY_FALLBACK

Current owner: ordered-index fallback reason reporting.

Source seams:

```text
DelosStorageOrderedIndexFallbackReason
MvccScanController#nonShortcutFallbackReason
MvccInheritedIndexMaintenance#recordOrderedIndexFallback
```

Algorithm rule: every shortcut decline should have a root-cause reason instead of silently dropping to a full scan.

### TEST_ONLY_PATH

Current owner: proof tasks and testing/diagnostic accessors.

Source seams:

```text
DelosStorageTableDiagnostics
DelosStorageDiagnostics
MvccBridgeDiagnosticsSupport
```

Algorithm rule: testing and diagnostics may expose counters or summaries, but those surfaces must not become hidden runtime authority.

## Reference influence

Calcite and HerdDB both point to the same next step: decisions should be explicit, explainable, and inspectable.

* Calcite contributes the idea of rule applicability, metadata, traits, and cost explanation.
* HerdDB contributes explicit index-operation shapes and small-engine lifecycle diagnostics.
* H2 contributes compact ordered lookup and inspector thinking.
* PostgreSQL and InnoDB contribute the rule that visibility and recovery authority must remain first-class algorithms.
* MapDB contributes compact key/row-id encoding ideas for later ordered-index page work.

This audit does not import any of those frameworks.

## Next slice

The next implementation slice may add a diagnostic record/report that captures:

```text
decision kind
provider/table identity
chosen/rejected/fallback state
reason
snapshot/read mode
shortcut safety result
row-id count if applicable
```

That next slice must remain diagnostic-only until a separate compatibility gate proves behavior changes are safe.
