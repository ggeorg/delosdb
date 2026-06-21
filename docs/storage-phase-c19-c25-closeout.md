# Storage Phase C19-C25 Closeout

This note records the completed Phase C routing/contract slice. It is a
consolidation document, not a new storage design.

## Closed slice

```text
C19 — review gaps closed
C20 — store-neutral table-access capability contracts
C21 — MVCC equality SELECT through DelosFilterableTableAccess
C22 — Derby heap compile-time honesty proof
C23 — MVCC UPDATE/DELETE through scan-produced row identities
C24 — Derby JavaCC / QueryTreeNode classifier proof
C25 — first regex route deletion
```

## Current execution shape

The first replaced MVCC read route now has this shape:

```text
SQL text
  -> Derby JavaCC parser
  -> QueryTreeNode classifier
  -> PlannedRoute / DelosPredicate
  -> DelosFilterableTableAccess.scan(context, mutableFilters, projection)
  -> VersionedStorageExecutionBridge / VersionedTable / VersionedIndex
```

The first mutation cleanup now has this shape:

```text
UPDATE / DELETE route
  -> DelosFilterableTableAccess.scan(...)
  -> DelosRowIdentity values from scan rows
  -> DelosMutableTableAccess.update/delete(...)
```

Mutation is by provider-native row identity. `DelosRowIdentity` is opaque and is
only meaningful to the table-access implementation that produced it.

## Contract boundary

The neutral table-access contracts live under:

```text
delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types
```

The four capability interfaces are intentionally small:

```text
DelosTableAccess
DelosFilterableTableAccess
DelosIndexableTableAccess
DelosMutableTableAccess
```

There is intentionally no `DelosStorageEngine` interface. The base contract has
identity, row shape, and capabilities only. Physical access is split by capability
interface so storeless can implement the base contract and decline physical access
cleanly.

`DelosAccessContext` uses typed keys and keeps `physicalAccessAllowed()` as a
first-class gate. Provider-specific context objects are carried by typed keys,
not by widening the neutral contract.

## Provider-specific adapters

Engine-side provider adapters belong under:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes
```

Current adapters/proofs:

```text
EngineMvccTableAccess       -> live MVCC adapter for C21/C23
EngineHeapTableAccessProof  -> compile-time heap honesty proof only
```

No new classes should be added under:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/store
```

## Heap boundary

Heap SQL is still the native Derby path:

```text
JavaCC parser -> binder/optimizer/compiler -> generated activation -> TransactionController / ScanController
```

C22 only proves that the new contracts can represent Derby heap concepts at
compile time. It does not route heap SQL through `DelosTableAccess`.

## Regex boundary

C25 deletes exactly one regex route:

```text
SELECT * FROM table WHERE column = literal
```

That route is now classified by Derby JavaCC / QueryTreeNode inspection. Remaining regex routes are temporary fallbacks and must not be deleted until a matching QueryTreeNode replacement exists.

## Honest outer boundary

The temporary MVCC SQL entry point still exists:

```text
EmbedStatement -> VersionedStorageSqlBridge.tryExecute(...)
```

C19-C25 reduce regex routing and add a real table-access execution boundary.
They do not yet move MVCC behind Derby binder/compiler/table metadata, and they
do not remove the pre-Derby-executor interception point.

## Reference-source lessons retained

Calcite contributed the useful idea of capability-segregated table interfaces
and mutable filter-list pushdown. DelosDB does not copy Calcite mutation APIs.

PostgreSQL contributed the execution-boundary lesson: table access must be an
explicit boundary carrying transaction, snapshot, visibility, and locking context.
DelosDB does not copy PostgreSQL's complete callback set.

MariaDB/MySQL remains the warning case: avoid one broad handler-style interface
with many unsupported methods.

## Next route rule

The next route replacement must follow the same rule as C24/C25:

```text
1. Add a Derby JavaCC / QueryTreeNode replacement route.
2. Prove it feeds the table-access contract.
3. Keep regex fallback while proving it.
4. Delete only the exact matching regex route in a later step.
```

Good next candidates are small and isolated: `CREATE INDEX` first if the parsed
node shape is clean, otherwise `INSERT`. Complex SELECT forms remain later work.
