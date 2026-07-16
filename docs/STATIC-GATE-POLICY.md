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
