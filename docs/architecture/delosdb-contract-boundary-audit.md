# DelosDB contract boundary audit

## Purpose

This document is the Module 23B decision record for the contract ownership map. The ownership map
classifies surfaces; this audit decides whether the current boundaries are dishonest enough to
justify a source move now.

This is a decision review, not a refactor. It does not move Java sources, create modules, rename
packages, promote engine-owned model/trace/diagnostics to public API, or touch storage, MVCC, optimizer, or query behavior.

## Source facts used

The audit is based on the current module graph after Module 23A and the dependency report generated
by:

```bash
./scripts/module-dependency-tree.py
```

The relevant source facts are:

```text
Modules: 22
Java sources: 3232
Inter-module source import edges: 8455
Project-package references: 956
Missing declared production dependencies: 0
Unresolved project imports: 0
Cross-module output-dir backdoor lines: 0
Package owner collisions: 0
```

The absence of owner collisions matters: the current package/module map is mechanically coherent.
The remaining questions are architectural ownership questions, not broken-build questions.

## Boundary verdicts

| Surface | Current role | Boundary verdict | Action now | Later question |
| --- | --- | --- | --- | --- |
| `delosdb-runtime-api` | Extracted inherited Derby runtime/service substrate. | Honest enough as substrate, but the module name can be misunderstood as “all DelosDB runtime contracts.” | Keep as-is. Do not add DelosDB model/trace contracts here. | Consider a future rename only if the build can do it without churn and after consumers are stable. |
| `delosdb-derby-store-api` | Extracted inherited Derby store/access/raw contracts. | Honest boundary. It is Derby store substrate, not DelosDB storage-provider API. | Keep as-is. | Later decide whether any store contracts are implementation detail rather than substrate. |
| `delosdb-storage-api` | DelosDB-owned storage-provider contract lane, currently in Derby package space for integration. | Correct module, mixed package identity. This is acceptable for now because real engine/storage consumers already depend on it. | Keep as-is. Do not move to runtime-api. | Future package migration to `io.github.ggeorg.delosdb.storage.api` only after consumers stabilize. |
| `delosdb-spi` | DelosDB provider/extension SPI. | Mostly honest SPI boundary. It is separate from runtime substrate and storage contracts. | Keep as-is. | Review overlap with `delosdb-storage-api` later; do not merge now. |
| `delosdb-engine` / `io.github.ggeorg.delosdb.engine.model` | Engine-owned RDBMS model vocabulary and small contracts. | Intended home. Not runtime API and not public SPI yet. | Keep engine-internal; restore this package boundary if code drifted into trace. | Promote only when a real production module outside engine needs it. |
| `delosdb-engine` / `io.github.ggeorg.delosdb.engine.trace` | Engine-owned trace/event machinery. | Honest only as observation machinery. It must not be treated as the model. | Keep engine-internal. | Move only if shared tracing has a real lower-level consumer. |
| `delosdb-engine` / `io.github.ggeorg.delosdb.engine.diagnostics` | Engine-owned reader-facing diagnostics. | Intended home for formatter, summary, and observed-plan reports. | Keep engine-internal. | Promote only if a public diagnostics consumer appears. |
| `delosdb-storage-mvcc` | Native MVCC implementation and research subsystem. | Implementation boundary is correct. Some concepts may become diagnostics in Phase 24, but they are not API yet. | Keep implementation-internal. | Phase 24 decides how to expose snapshot, WAL, checkpoint, vacuum, and visibility observations. |
| `delosdb-storage-bridge` | Temporary Derby-facing MVCC access-method adapter. | Honest only as compatibility scaffolding. It must not become the shared architecture layer between Derby storage and native MVCC. | Keep temporary and documented. | Retire or shrink when provider contracts become sufficient. |
| `delosdb-storage-derby` | Inherited Derby heap/raw/btree implementation. | Honest implementation boundary after the stale JPMS scaffold removal. | Keep as classpath-compiled inherited storage implementation. | Later decide which common btree/sort/raw pieces are shared substrate versus Derby-provider implementation. |
| `delosdb-storage-io` | DelosDB page/volume support code. | Honest implementation/support boundary, not general runtime API. | Keep as support module. | It may become a stronger low-level storage foundation if MVCC and future providers converge on it. |
| `delosdb-storeless` | Test/demo provider surface. | Not a contract owner. | Keep only as proof/demo if useful. | Remove if it stops proving provider independence. |

## Detailed decisions

### D23B-1 — Do not turn `delosdb-runtime-api` into a DelosDB contract bucket

`delosdb-runtime-api` contains Derby package surfaces such as:

```text
org.apache.derby.iapi.services.*
org.apache.derby.iapi.util
org.apache.derby.iapi.xml
org.apache.derby.io
```

It has 132 Java source files across 20 packages and is overwhelmingly inherited Derby runtime and
service substrate. Its role is not “all DelosDB contracts.” Therefore Module 21/22 model and trace
vocabulary must not be moved here.

One small impurity is that several runtime monitor interfaces import
`io.github.ggeorg.delosdb.spi.annotation.LegacyInternal`. This is an annotation dependency, not a
runtime contract dependency. It is acceptable for now, but it should remain visible as a possible
later annotation cleanup if the project wants a purer Derby-substrate module.

Decision:

```text
Keep delosdb-runtime-api as inherited Derby runtime/service substrate.
Do not add new DelosDB-owned contracts to it.
Do not rename it in this pass.
```

### D23B-2 — Keep `delosdb-storage-api` as the DelosDB storage contract lane

`delosdb-storage-api` contains 54 Java source files under `org.apache.derby.iapi.store.types`. The
class names show its real role:

```text
DelosTableAccess
DelosStorageProviderFactory
DelosStorageTable
DelosStorageScan
DelosStorageTransaction
DelosPredicate
DelosRange
DelosProjection
DelosTableCapability
DelosTableGuarantee
StoreDataValue
StoreRowLocation
StoreTypeSupport
```

This is the DelosDB-owned storage-provider contract lane. The package name remains Derby-shaped
because the contracts are still integrated through inherited Derby execution and storage value
surfaces. That is a packaging compromise, not evidence that the contracts belong in
`delosdb-runtime-api`.

Decision:

```text
Keep storage-provider contracts in delosdb-storage-api.
Document the package identity as transitional/honest-for-integration.
Do not move these contracts to runtime-api.
```

Known compromise:

```text
Existing DelosDB storage contracts may remain in Derby package space while Derby integration
requires it. New pure DelosDB contracts should not automatically be added under Derby packages
unless the integration point proves that package placement is necessary.
```

### D23B-3 — Keep `delosdb-derby-store-api` as Derby store substrate

`delosdb-derby-store-api` contains Derby access, conglomerate, raw, log, transaction, and replication
contract packages:

```text
org.apache.derby.iapi.store.access
org.apache.derby.iapi.store.access.conglomerate
org.apache.derby.iapi.store.raw
org.apache.derby.iapi.store.raw.log
org.apache.derby.iapi.store.raw.xact
```

This is not the DelosDB storage-provider contract layer. It is the inherited Derby store substrate
needed by the engine and inherited storage implementation.

Decision:

```text
Keep delosdb-derby-store-api separate from delosdb-storage-api.
Do not collapse Derby store substrate and DelosDB storage contracts.
```

### D23B-4 — Keep `delosdb-spi` separate from storage-api for now

`delosdb-spi` contains provider-facing extension surfaces for functions, indexes, storage, versioned
storage, and types. It is DelosDB-owned, but it is not the same layer as `delosdb-storage-api`.

The current distinction is useful:

```text
delosdb-storage-api
  engine/storage contract lane used by inherited execution integration

delosdb-spi
  provider/extension-facing SPI lane
```

There may be overlap later, especially around storage and versioned storage. But merging now would
be speculative and would blur the difference between engine-facing contracts and provider-facing
extension points.

Decision:

```text
Keep delosdb-spi separate.
Review overlap later only with concrete duplicated responsibilities.
```

### D23B-5 — Keep engine model, trace, and diagnostics engine-owned, but do not confuse them

The correct ownership decision is not that the model belongs in `engine.trace`. The correct decision
is that the engine-owned RDBMS model, trace machinery, and diagnostics remain inside
`delosdb-engine` until real production consumers require promotion.

The intended internal split is:

```text
io.github.ggeorg.delosdb.engine.model
  RDBMS building-block vocabulary and small contracts

io.github.ggeorg.delosdb.engine.trace
  trace events, sinks, registry, and Derby hook helpers

io.github.ggeorg.delosdb.engine.diagnostics
  formatter, summary, and observed-plan reports
```

`engine.trace` is not the modern RDBMS model. Trace is an observability mechanism that records
model facts from real Derby/DelosDB execution points. If model classes currently live under
`engine.trace`, that is package drift to correct inside `delosdb-engine`, not a reason to move the
model to `delosdb-runtime-api` or to create a new Gradle module.

Decision:

```text
Keep engine-owned model/trace/diagnostics inside delosdb-engine.
Restore internal package separation: engine.model, engine.trace, engine.diagnostics.
Do not promote these packages to API/SPI.
Do not move them to delosdb-runtime-api.
```

### D23B-6 — Keep MVCC internals internal until Phase 24

`delosdb-storage-mvcc` contains native MVCC concepts that are important to the modern RDBMS model:
transactions, snapshots, visibility, version chains, WAL-like log records, checkpoints, vacuum,
page-backed storage, and durable row/index stores.

Those are valuable research concepts, but Module 23 is layout decision work. Phase 24 is the right
place to expose MVCC observations.

Decision:

```text
Do not promote MVCC internals to contracts in Phase 23.
Use Phase 24 to expose MVCC research observations alongside the engine model and diagnostics, without making storage modules depend on delosdb-engine.
```

### D23B-7 — Treat the bridge as temporary compatibility code

`delosdb-storage-bridge` uses inherited Derby package space:

```text
org.apache.derby.impl.store.access.mvcc
```

That is correct for what it currently does: adapt Derby access-method wiring to MVCC/provider
experiments. It is not the future architecture authority.

The bridge must not become the shared architecture layer between `delosdb-storage-derby` and
`delosdb-storage-mvcc`. It must not own common storage contracts, shared btree/sort code, or the
final provider shape. The intended provider relationship remains:

```text
                         delosdb-storage-api
                           ↑              ↑
                           |              |
              delosdb-storage-derby   delosdb-storage-mvcc
```

not:

```text
engine -> delosdb-storage-derby -> delosdb-storage-bridge -> delosdb-storage-mvcc
```

Decision:

```text
Keep the bridge honest as temporary adapter code.
Do not move shared contracts into the bridge.
Do not let bridge package shape dictate final storage architecture.
```

## Boundary conclusions

The current layout is not perfect, but it is good enough to avoid broad movement.

The most important conclusion is:

```text
No Module 23 source move is justified by the current evidence.
```

The current module graph has no missing declared production dependencies, unresolved project imports,
package-owner collisions, or cross-module output-directory backdoors. The architecture pressure is
therefore conceptual and naming-related, not a build integrity failure.

The chosen follow-up route is documentation closeout:

```text
Record that the initial Phase 23 layout decision found no immediate source move.
Clarify that delosdb-runtime-api is inherited Derby runtime/service substrate.
Move next to Phase 24 MVCC research observations unless a concrete production dependency forces a
layout move first.
```

This closeout does not rename `delosdb-runtime-api`; it only prevents the module from becoming a
catch-all bucket for DelosDB-owned contracts. Future layout moves require new evidence from Phase 24
or later work.

## Deferred candidates

These are not Module 23B work items:

| Candidate | Deferred because |
| --- | --- |
| Rename `delosdb-runtime-api` | Would create churn without changing the executable model. |
| Move engine model/trace/diagnostics to a new module | No production consumer outside engine needs it yet. Internal package separation is enough. |
| Move storage contracts out of Derby package space | Integration is still Derby-shaped; move later when seams harden. |
| Merge `delosdb-spi` and `delosdb-storage-api` | Possible overlap is not yet a concrete duplication problem. |
| Promote MVCC concepts to API | Belongs to Phase 24 observations, not Phase 23 layout audit. |
| Rename `Rdbms*` trace classes | Cosmetic churn; class names are not the ownership problem. |

## Verification expectation

This pass is documentation-only. The normal verification lane should remain:

```bash
./gradlew clean
./gradlew roadmapVerification
./scripts/module-dependency-tree.py
```

Expected dependency-report result:

```text
Missing declared production dependencies: 0
Unresolved project imports: 0
Cross-module output-dir backdoor lines: 0
```
