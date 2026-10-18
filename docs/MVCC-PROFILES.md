# Supported MVCC profiles

A profile is a configuration DelosDB supports for `delos_mvcc` tables: what it guarantees and which
tests prove it. Everything else the engine can be switched to is an experiment.

Every `delosdb.*` system property the engine reads is classified in
[`gradle/static-analysis/delosdb-engine-properties.tsv`](../gradle/static-analysis/delosdb-engine-properties.tsv).
`delosRepositoryIntegrityStaticAnalysis` fails when the engine reads a name that is not classified,
when a classified name is no longer read, or when a supported entry names no registered proof test.

## Profile 1: default

No `delosdb.*` property is set. Tables created `using delos_mvcc` use the RawStore-backed row
format, with:

- concurrent commit publication;
- physical scan and row-location costing for the optimizer;
- vacuum when `SYSCS_UTIL.SYSCS_INPLACE_COMPRESS_TABLE` runs, and no background maintenance.

The heap-side behaviours that are on by default (insert lanes, cached-page validity, held
intent-lock regrant, insert root routing) belong to this profile too. Their off switches exist for
diagnosis and are not supported: no test runs with them off.

Proofs, by suite in `gradle/testing/delos-test-suite-registry.tsv`:

| Concern | Tests |
|---|---|
| Snapshot isolation and locking | `DelosIsolationSpecificationTest`, `HeapMvccIsolationDifferentialTest`, `MvccRawStoreLogicalLockingTest`, `MvccRawStorePhysicalLockingTest`, `MvccSqlDatabaseRuntimeIsolationTest` |
| SQL behaviour against heap | `HeapMvccDifferentialSqlHarnessTest`, `HeapMvccDifferentialFuzzTest`, `MvccSqlFeatureCompletenessTest`, `MvccSqlTypeCoverageTest` |
| Constraints and indexes | `MvccRawStoreUniqueConstraintTest`, `MvccUniqueIndexKeyReuseTest`, `MvccSecondaryIndexReclamationTest`, `BTreePrefixSnapshotReadTest`, `MvccCheckTableTest` |
| Vacuum | `MvccRawStoreVacuumTest`, `MvccInplaceCompressTest` |
| Recovery | `MvccRawStoreRecoveryTest`, `MvccRawStoreDecisionWalCrashTest`, `DelosDdlCrashAtomicityTest`, `MvccSqlBackupRestoreTest`, `HeapMvccDrdaServerRestartTest` |
| Network access | `MvccDrdaNetworkClientTest`, `MvccDrdaConcurrentNetworkClientTest`, `V1ModularImageDrdaTest` |
| Heap compatibility with Derby | `runDelosHeapDerbyStaticCompatibilityContract`, `runDelosHeapDerbyDynamicCompatibilityContract` |

## Profile 2: default with background maintenance

As profile 1, with `delosdb.mvcc.rawStoreMaintenance.enabled=true`. The maintenance service vacuums
tables in the background (see [`MVCC-MAINTENANCE.md`](MVCC-MAINTENANCE.md)).
`delosdb.mvcc.rawStoreMaintenance.periodMillis` and
`delosdb.mvcc.rawStoreMaintenance.changedRowsThreshold` tune it.

Proofs: everything in profile 1, plus `MvccRawStoreMaintenanceDiagnosticsTest` and
`MvccSqlLongReaderPurgeStressTest`, which runs a long repeatable-read reader against background
maintenance.

## Settings supported in either profile

| Property | Default | Proof |
|---|---|---|
| `delosdb.mvcc.currentRowReadCache.slots` | 4096 | `MvccCurrentRowArchitectureBoundedCacheTest` |
| `delosdb.memory.maxBytes` | 268435456 | `MvccRawStoreMemoryDatabaseTest` |

`delosdb.mvcc.snapshotLeaseRegistry.slots` and
`delosdb.mvcc.rawStoreCommitSequenceReservationBlockSize` are validated at boot, but only their
defaults are tested, so changing them is not supported.

## Experiments

Experiments are off by default and are not supported, alone or combined. They are covered by
focused tests and benchmark tasks, not by the proofs above, and a database created with an
experiment on may not open in a later build.

- The Gen2 row formats (`mvccGen2A1`, `mvccGen2B.pk`, `mvccGen2C1.history`,
  `mvccGen2C2.archivedUndo`) and their read and commit options: projected, single-pass, reusable
  and streaming CURRENT scans, the fast-fetch modes, batched and WAL-batched commit stamping, and
  transaction-status visibility with its cache.
- `mvccBaseFetchPagePrefetch`, `rawStorePreparedInsert`, `heapPageReadImage`,
  `btreeInsertBranchRoutingSnapshot`, and `fastRecordReadLock` with its retained slots.
- The optimizer experiments `delosdb.optimizer.predicatePushdown` (and `.consume`) and
  `delosdb.optimizer.costModelProvider`. No test sets `costModelProvider`.

`delosdb.diagnostic.*` properties only observe; `delosdb.mvcc.rawStoreVerticalSlice.failurePoint`
injects crashes for recovery tests.

## Known gaps

- No automated test proves that an MVCC database written by one DelosDB build opens in a later
  build. The heap format is covered by the Derby compatibility contracts; the MVCC format is not.
- The two physical-costing switches are named `experimental` but are on by default and part of
  profile 1.
