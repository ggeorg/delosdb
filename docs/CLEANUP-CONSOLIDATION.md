# Cleanup and consolidation

## Status

Active v1.0 program slice.

Phases 1-7 are complete. The current cleanup work promotes stable product truth into tracked
README and architecture documents, removes obsolete proof routing, deletes confirmed dead code,
and prepares the codebase for later ownership extraction without changing supported semantics.

## Objectives

```text
one authoritative SQL-to-result path
one authoritative behavior for each production switch
no phase-named routing properties in production code
no supported production class described as a prototype or skeleton
no dead authored class retained as future scaffolding
current tracked documentation separated from historical evidence
compatibility and durable formats unchanged by cleanup
```

## Classification before removal

Every candidate is classified as one of:

```text
compatibility-required inherited behavior
supported DelosDB product behavior
permanent regression or diagnostic obligation
test-only comparison path
historical evidence
stale transition surface
confirmed dead code
```

Only the last two categories are deleted directly. Historical evidence is consolidated or moved to
`docs/history/` rather than left in the current documentation path. Compatibility readers, durable
format decoders, candidate-index quarantine checks, and provider-first write assertions remain
because they still protect supported data or permanent invariants.

## Current consolidation slice

This slice removes phase-named heap proof routes that were disabled by default and had no current
product authority:

```text
heap cost proof observation
heap table-scan probe and shadow result set
property-gated heap SELECT/INSERT/UPDATE/DELETE alternatives
CREATE INDEX metadata observation
obsolete MVCC CRUD property guard and its self-test
```

The authoritative Derby heap result sets and optimizer behavior remain unchanged.

It also deletes confirmed unreferenced authored classes and unused private helpers, replaces
stale milestone language in supported storage classes with present-tense ownership descriptions,
and consolidates completed phase documents into current protocol authorities and a small
`docs/history/` provenance set.

## Permanent rule

Production Java source must not contain system-property routing names beginning with:

```text
delosdb.storage.phase
```

`delosNoTransitionalStorageRoutingStaticAnalysis` enforces this rule and rejects the retired proof
classes if they reappear in main source.

Research and comparison behavior must be:

```text
test-only
package-private where practical
explicitly invoked by a benchmark or experiment
removed when the experiment is accepted or rejected
```

## Documentation consolidation

Tracked documentation is organized as:

```text
product and architecture authority
current protocol authority
current engineering program
permanent audits and regression evidence
historical closeout records
```

The index is [`README.md`](README.md). Phase-numbered intermediate design documents are consolidated
into current protocol documents or moved to `docs/history/` after their phase closes.

## Required verification

Focused proof:

```bash
./gradlew delosNoTransitionalStorageRoutingStaticAnalysis
./gradlew :delosdb-engine:compileJava
```

Behavior regression:

```bash
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
```

Normal closeout:

```bash
./gradlew s0CloseoutVerification
```

## Subsequent consolidation work

Later slices will address, in order:

1. security and product-truth corrections already identified for Phase 8;
2. frozen benchmark and resource baseline;
3. false or metadata-only extension surfaces;
4. diagnostic snapshot extraction;
5. commit-pipeline and table-health ownership extraction.

A later slice must not be started merely because the current one is tedious.
