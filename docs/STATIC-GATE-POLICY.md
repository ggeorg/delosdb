# DelosDB static gate policy

## Purpose

Static gates protect stable engineering boundaries before slower integration tests run. They must
validate source, artifacts, dependencies, or repository safety—not roadmap prose.

## S0 criteria

A gate belongs in `s0CloseoutVerification` when it is:

- deterministic;
- fast enough for normal iteration;
- based on source, bytecode, artifacts, dependency metadata, or exact repository structure;
- stable across harmless wording or method-local refactoring;
- responsible for a continuing product or compatibility invariant.

Current examples include:

```text
storage and server structural analysis
heap compatibility and deserialization integration
runtime provider discovery
module dependency boundaries
Derby module parity
workspace and cleanup-script hygiene
fork-diff classification
absence of transitional storage routing
```

## Prohibited S0 criteria

S0 must not fail because a roadmap or closeout document uses different prose.

Examples that do not belong in S0:

```text
phase status wording
commit-message text
overlay file names
"current" or "closed green" markers
document-only proof rows
benchmark thresholds
long-running external tools
```

Performance, JMH, JFR recording runs, jcstress, SQLancer, long-reader soak, and fault campaigns remain
opt-in or phase-specific validation.

## Transitional routing rule

Production source must not retain phase-named system-property routes such as:

```text
delosdb.storage.phase...
```

These switches were useful while establishing experimental heap/provider paths, but they are not a
supported product configuration surface. The authoritative heap path is Derby's normal result-set
and access-method route; MVCC selection comes from persisted table and conglomerate identity.

`delosNoTransitionalStorageRoutingStaticAnalysis` verifies that the phase-named properties and
retired proof classes do not reappear in main source.

## Historical gate cleanup

Roadmap/prose gates removed after earlier closeouts include:

```text
delosBalancedStorageModernizationCloseoutStaticAnalysis
delosStorageModernizationTradeoffAuditStaticAnalysis
delosStorageModernizationTradeoffAuditRound2StaticAnalysis
delosNextEngineDepthRoadmapContractsStaticAnalysis
delosSharedStorageServiceExtractionAuditStaticAnalysis
```

Their useful conclusions remain in source, tests, current protocol documents, or historical records.
They are not executable release authority.

## MVCC database runtime ownership

`delosMvccDatabaseRuntimeOwnershipStaticAnalysis` is a stable S0 gate. It verifies that:

* `MvccConglomerate` does not select a database through mutable JVM-global state;
* `MvccDatabaseRuntime` owns the database-scoped provider store and table-state registry;
* `MvccConglomerateFactory` acquires that runtime from its explicit Derby database directory;
* MVCC diagnostics can bind to an explicit database context;
* SQL and metadata tests do not use directory-free MVCC diagnostics;
* explicit statistics and optimizer metadata hooks bind to Derby's owning database service;
* embedded SQL reopen tests activate persisted MVCC base tables through `LOCK TABLE ... IN SHARE MODE`;
* reopen helpers do not invoke internal transaction APIs outside Derby's JDBC context or scan user rows;
* `RAMAccessManager` stops ServiceLoader-booted access-method factories and releases their runtime leases.

Run it directly with:

```bash
./gradlew delosMvccDatabaseRuntimeOwnershipStaticAnalysis
```

## MVCC table rebuild provider truth

`delosMvccTableRebuildProviderTruthStaticAnalysis` is a stable S0 gate. It verifies that inherited
base-table rebuild DDL cannot reach the hard-coded Derby heap replacement path for an MVCC table.
The gate requires early provider-aware rejection for offline compress, truncate, and drop-column
rebuilds. Directly exposed rebuild DDL such as `TRUNCATE TABLE` and `ALTER TABLE ... DROP COLUMN`
must return stable SQLState `0A000`. Offline compression is exposed publicly only through the
`SYSCS_COMPRESS_TABLE` Java routine: JDBC callers receive Derby's outer routine SQLState `38000`,
and the exception chain must retain the underlying engine SQLState `0A000`. The gate also requires
provider-preserving in-place maintenance in the long-row proof and proof that rejected operations
preserve both data and `delos_mvcc` identity across reopen.

Run it directly with:

```bash
./gradlew delosMvccTableRebuildProviderTruthStaticAnalysis
```
