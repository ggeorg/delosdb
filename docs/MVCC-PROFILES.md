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
| Reopen across builds | `delosMvccCrossBuildReopen` (below) |

## Reopening databases written by an earlier build

`./gradlew :delosdb-tests:delosMvccCrossBuildReopen` builds the runtime jars of a pinned earlier
commit (5c01dc0d by default, `-Pdelosdb.crossBuild.earlierCommit=<commit>` to change it; it must be a
strict ancestor of HEAD) from git history in its own directory. For each profile, in separate JVMs,
that build writes two databases, one shut down cleanly and one halted right after its last commit,
with a heap and a `delos_mvcc` table: committed inserts, updates (including unique-key changes),
deletes, unique keys reused by new rows, and a rolled-back delete. The current packaged runtime then
reopens each database and checks every row against a model of the workload, secondary, unique and
primary-key index lookups, `SYSCS_CHECK_TABLE`, that duplicate primary and unique keys are rejected,
and that it can write and reopen again. Each JVM reports the jar it loaded the engine from, and the
task fails unless they are the earlier and the current `derby.jar`. The report is
`build/reports/delosdb/mvcc-cross-build-reopen.txt`.

This is the cross-build guarantee; the restart and crash tests under Recovery reopen databases with
the build that wrote them. The task needs the repository history, so it runs in
`releaseVerification`, not in `check` or CI.

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

- Cross-build reopen is proved from one pinned earlier build, 5c01dc0d (2026-10-14), not from every
  earlier commit, and only for the workload above.
- The two physical-costing switches are named `experimental` but are on by default and part of
  profile 1.
