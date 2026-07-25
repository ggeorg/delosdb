# JDK 25 RawStore final production closeout

## Status

```text
VERIFIED
```

## Decisions

```text
REMOVE_RETAINED_PHASE8_ORACLE
KEEP_RAWSTORE_IO_SNAPSHOT_SCHEMA_3
GROUP_INTERNAL_SNAPSHOT_CONSTRUCTION
```

## Repository authority

The Phase 8 source oracle no longer has a production, migration, differential, or release role. Its
126 production-like sources, 80 tests, page-volume implementation, jcstress probes, source set, and
dormant Gradle tasks are deleted. Git history and accepted baseline evidence preserve the experiment.

`delosdb-storage-mvcc` now contains one production source set with 20 Java classes. It publishes one
provider patch artifact and two service descriptors. There is no retained source set and no second
storage authority.

## Current verification replacement

The following live proofs replace archived-oracle task dependencies:

```text
RawStore vertical slice
RawStore decision/WAL crash recovery
heap/MVCC differential SQL
network-client concurrency
long-reader purge stress
shared RawStore deterministic fault injection
page-I/O representation benchmark
```

Performance and external-validation adapters use these current tests. The accepted v1 baseline keeps
historical files as immutable evidence but new captures use live Stage 8 lanes.

## Diagnostics maintainability

The public `DelosRawStoreIoSnapshot` schema remains version 3 and its flat accessors remain stable.
Construction is no longer one long positional list of primitive values. Package-local named groups
carry:

```text
PageIo
ForceIo
ChannelRecovery
RuntimeState
```

`DelosRawStoreIoMetrics.snapshot()` builds those groups and calls one package-local capture factory.
The record constructor still enforces non-negative counters, peak relationships, recovery outcomes,
and shutdown-leak invariants.

## Stale validation and observability cleanup

The closeout removes retained-oracle-only static tasks and rewires remaining proof surfaces to live
RawStore-backed tests. The ordered-index NULL-key gate now uses the SQL integration proof. The JFR
surface keeps only `recordMvccAnalyzeStatistics(...)`, the sole event method with a live producer;
unwired Phase 8 event sketches are deleted.

## Code-size outcome

The MVCC module build shrinks from approximately 1,500 lines to fewer than 100 lines. More than 200
archived Java files and the stale in-tree jcstress probe tree are removed. No replacement production
abstraction is introduced.

## Permanent gates

```text
delosMvccRetainedRuntimeRetirementStaticAnalysis
delosStorageIoModuleRetirementStaticAnalysis
delosPerformanceConcurrencyValidationStaticAnalysis
delosSharedRawStoreProductionCloseoutStaticAnalysis
delosModuleDependencyBoundaryStaticAnalysis
```

## Complete retired-surface enforcement

The final closeout uses one authoritative manifest containing every retired Stage 8 file path. The
cleanup script removes all listed files, the retired storage modules, the complete Phase 8 MVCC
source/test roots, and the stale jcstress tree. It is idempotent and verifies that no path remains.

The permanent closeout gate additionally scans active production, test, benchmark, and build files
for retired package and class names. This identified two remaining references to the deleted
`MvccFailurePointRegistry`: the superseded `MvccTransactionalDdlCrashTest` and one obsolete failure
method in `MvccSqlTransactionalDdlTest`. Both are removed. The live
`MvccRawStoreDecisionWalCrashTest` remains the sole process-halt proof around the inherited RawStore
commit decision.

This closes both forms of residue:

```text
retired files or empty source roots in the working tree
active code that still names a retired implementation
```

## Transactional DDL and RawStore state correction

The final RawStore-backed provider does not publish table state below the retired
`delos_mvcc/inherited-store` sidecar. Transactional DDL proofs therefore inspect
catalog conglomerate identity, SQL visibility, reopen behaviour, and the absence
of retired backup artefacts.

When a transaction performs DML and then drops that MVCC table, transaction-local
versions, allocator reservations, and private ordered-index generations for the
dropped table are excluded from commit publication. A private ordered-index
generation is dropped in the same RawStore transaction. Rollback to a savepoint
restores the RawStore containers and retains the transaction-local generation,
so DML performed before the savepoint still commits correctly after the DROP is
rolled back.

## Transactional test readback boundaries

Focused JDBC tests that disable auto-commit must end the explicit mutation
transaction before performing catalog/readback assertions or invoking the
backup procedure. The readback/backup phase switches back to auto-commit so
try-with-resources never closes an embedded Derby connection with an active
read or CALL transaction. This is test transaction hygiene only; it does not
change production commit, rollback, DDL, or backup semantics.

