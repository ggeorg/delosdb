# MODULE8A-0 storage-derby common-ground map

This is a small source-study pass before MODULE8 RowLocation hardening. It is not a new MVCC feature and it does not change runtime behavior.

## Purpose

Before adding more MVCC RowLocation and row-directory code, map what `delosdb-storage-derby` already provides and where MVCC should reuse inherited Derby contracts instead of inventing a second store/access layer.

The rule for the next phase is:

```text
Reuse Derby store/access contracts where they are provider-neutral.
Reuse heap/raw-store code only as a contract pattern, not as MVCC physical truth.
```

## Directly reusable code

### `org.apache.derby.iapi.store.access.RowUtil`

`RowUtil` is reusable by MVCC because it is store/access contract code, not heap-specific physical storage.

Useful direct helpers:

```text
RowUtil.qualifyRow(row, qualifiers)
RowUtil.newRowFromTemplate(...)
RowUtil.newRowFromTemplatePreservingArrayType(...)
RowUtil.toString(...)
```

Current state after MODULE7B:

```text
MvccScanController already reuses RowUtil.newRowFromTemplatePreservingArrayType(...)
for Derby bulk scan row-array materialization.
```

Likely cleanup target:

```text
replace custom MVCC qualifier-evaluation logic with RowUtil.qualifyRow(...)
```

That should be a dedicated no-behavior-change cleanup, not hidden inside RowLocation hardening.

## Reusable as contract patterns

### `org.apache.derby.impl.store.access.conglomerate.GenericScanController`

This class is useful as a reference for the inherited `ScanController` contract:

```text
fetchNextGroup(...)
row-location array materialization
reopenScanByRowLocation(...)
scan state transitions
RowUtil row-template usage
```

It is not a good direct base class for MVCC today because it assumes raw-store internals:

```text
OpenConglomerate
RowPosition
RecordHandle
raw pages
raw latches
raw container/page fetch semantics
```

MVCC should copy the contract behavior where needed, not inherit raw-store identity.

### `org.apache.derby.impl.store.access.heap.HeapScan`

`HeapScan` is the best local example for how Derby expects scan and row-location methods to behave.

Useful as a pattern:

```text
positionAtRowLocation(...)
fetchLocation(...)
fetchNextGroup(...)
reopenScanByRowLocation(...)
row-location array reuse
```

Not reusable as MVCC implementation because heap row locations are physical heap locations.

### `org.apache.derby.impl.store.access.heap.HeapRowLocation`

`HeapRowLocation` is the best model for Derby-facing `StoreRowLocation` mechanics:

```text
StoreDataValueBase + StoreRowLocation shape
cloneValue(...)
recycle(...)
setFrom(...)
compare(...)
writeExternal(...)
readExternal(...)
unwrapStoreRowLocation(...)
```

MVCC should reuse this Derby-facing shape, but not its identity semantics.

Heap identity:

```text
page + record id = physical identity
```

MVCC identity:

```text
rowId = stable logical identity
version locator = optional physical hint
```

## Already-correct MVCC common ground

### `MvccConglomerate extends GenericConglomerate`

This is the right level of shared Derby store/access inheritance. It keeps MVCC inside the inherited conglomerate dispatch shape without forcing MVCC into heap/raw pages.

### `MvccScanController implements ScanManager`

This is acceptable for now. The class should continue learning Derby scan-contract behavior from `GenericScanController` / `HeapScan`, while remaining MVCC-owned.

### `MvccRowLocation extends StoreDataValueBase implements StoreRowLocation`

This is the correct Derby-facing shape. The next MODULE8 proofs should focus on its semantics:

```text
rowId is authority
locator hint may be stale
lookup must re-read by rowId and snapshot
stale hint must not resurrect deleted rows
restart must preserve logical row identity
```

## Do not reuse directly now

Do not force MVCC through these raw-store-specific internals:

```text
OpenConglomerate
RowPosition
RecordHandle
raw Page
raw latches
HeapController physical update/delete semantics
BTreeController / BTreeScan internals
```

Those are useful to study, but they encode heap/B-tree physical truth. MVCC must keep logical row identity and visibility recheck as authority.

## Immediate next move

Proceed to:

```text
MODULE8A — RowLocation update/restart proof
```

Runtime-only goal:

```text
A RowLocation captured before UPDATE still resolves by rowId after UPDATE + COMMIT + shutdown/reopen.
```

No indexes, no optimizer work, no range predicates, no native I/O, and no brittle source guards in behavior smokes.
