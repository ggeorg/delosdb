# V1 RawStore MVCC retained source-oracle retirement

## Status

```text
VERIFIED
```

## Final decision

```text
REMOVE_RETAINED_PHASE8_ORACLE
```

The Phase 8 MVCC implementation is no longer a runtime, build target, test source set, benchmark
implementation, or repository source archive. The source archive is deleted from the working tree.
The live `delos_mvcc` provider is the RawStore-backed
implementation under `org.apache.derby.impl.store.access.mvcc`.

## Why deletion is correct

The archived code had already lost every production responsibility:

```text
no provider service
no production source-set membership
no runtime artifact
no normal-check dependency
no S0 dependency
no supported database-format reader
```

Keeping 126 production-like Java files, 80 tests, a second source set, and dozens of dormant Gradle
tasks created permanent code-search, duplication, security-review, and maintenance ambiguity. Git
history preserves the complete implementation and its verification evidence without keeping it in the
working tree.

## Final module boundary

`delosdb-storage-mvcc` now contains:

```text
20 production Java classes
2 provider service descriptors
1 compact production build
no retained source set
no archived tests
no page-volume implementation
```

The build publishes only the production provider patch artifact through
`derbyRuntimePatchElements`. Normal `check` compiles and verifies only the live provider.

## Baseline and validation replacement

Current evidence replaces archived-oracle tasks:

```text
RawStore vertical slice
RawStore decision/WAL crash recovery
heap/MVCC differential SQL
network-client concurrency
long-reader purge stress
shared RawStore fault injection
page-I/O representation decision
```

Historical accepted baseline files remain immutable evidence; they are not executable source.

## Compatibility boundary

Existing Phase 8 private-format files still fail closed. Removal of the source oracle does not add an
implicit reader or migration path. Migration requires an explicit external export/import tool if one
is ever approved.

## Permanent gate

`delosMvccRetainedRuntimeRetirementStaticAnalysis` verifies source archive deletion, compact build
wiring, current proof tasks, runtime artifacts, service boundaries, documentation, and S0 inclusion.

## Retired database-snapshot proofs

The old `MvccDatabaseStorageSnapshotTest` and
`MvccTableTransactionSnapshotTest` exercised the external persistence runtime's
`DelosDatabaseStorageSnapshot` implementation. They are not RawStore proofs.
After authority cutover, `MvccStorageDiagnostics.databaseStorageSnapshot()`
intentionally fails closed, and `MvccRawStoreAuthorityCutoverTest` preserves
that contract.

The two obsolete focused tasks and the historical
`docs/MVCC-DATABASE-STORAGE-SNAPSHOT.md` description are removed. Current
RawStore diagnostics are proved through maintenance, memory, RawStore I/O,
storage-path, and transaction-registry tests. The permanent retirement gate
rejects restoration of either stale test, task, or document.

