# DelosDB contract ownership map

## Purpose

This document classifies the visible contract and API surfaces in the current DelosDB module graph.
It is a Phase 23 planning artifact: it records ownership and future-placement pressure without
moving code, renaming packages, creating modules, or promoting internal diagnostics to public API.

The key rule for Phase 23 is:

```text
Do not move a contract because it is conceptually attractive.
Move it only when the current dependency graph and real consumers prove that it belongs somewhere else.
```

## Non-goals

This pass does not:

```text
move Java sources
rename modules
rename packages
rename Rdbms* trace classes
promote engine.trace to API or SPI
touch MVCC behavior
touch optimizer behavior
split delosdb-runtime-api
relocate inherited Derby contracts
```

## Ownership categories

The current codebase has several different kinds of contracts. They should not be collapsed into one
module just because they are all visible APIs.

| Category | Meaning | Default rule |
| --- | --- | --- |
| Inherited Derby runtime substrate | Low-level Derby service, monitor, cache, lock, I/O, context, class loading, and utility contracts needed by the inherited engine/runtime. | Keep Derby identity and do not mix with new DelosDB model contracts. |
| Inherited Derby store substrate | Derby access/store/raw/btree contract surfaces that remain part of the inherited storage path. | Keep separate from DelosDB storage-provider contracts. |
| DelosDB storage-provider contracts | DelosDB-owned contracts that let the engine talk to storage providers. | Belong in the storage contract lane, not runtime-api. |
| DelosDB SPI contracts | Explicit DelosDB extension/provider SPI surfaces. | Keep distinct from Derby substrate and from engine diagnostics. |
| Engine-owned model/diagnostic vocabulary | Trace/model types proven against real Derby execution but currently owned by delosdb-engine. | Keep internal until real production consumers outside the engine exist. |
| DelosDB implementation internals | Native MVCC/page/storage-IO implementation code. | Do not promote to API because another package wants to observe it. |
| Compatibility adapters | Temporary bridge code that adapts inherited Derby access paths to DelosDB concepts. | Treat as implementation scaffolding, not a long-term contract home. |

## Current module ownership map

| Module / surface | Current ownership | Current role | Current decision | Candidate future question |
| --- | --- | --- | --- | --- |
| `delosdb-runtime-api` | Inherited Derby substrate, even though packaged as a DelosDB Gradle module. | Low-level `org.apache.derby.iapi.services.*`, `org.apache.derby.iapi.util`, `org.apache.derby.iapi.xml`, and `org.apache.derby.io` runtime/service contracts. | Do not add Module 21/22 trace/model contracts here. Do not treat this as the home for all DelosDB APIs. | Should this eventually be renamed or split to make the Derby-substrate role clearer? |
| `delosdb-derby-store-api` | Inherited Derby store substrate. | Derby store/access/raw/btree contract lane used by inherited storage and engine paths. | Keep separate from DelosDB storage-provider contracts. | Which store interfaces are still true substrate versus provider implementation detail? |
| `delosdb-storage-api` | DelosDB-owned storage contract lane, currently anchored in Derby `org.apache.derby.iapi.store.types` package space for integration. | Provider-neutral table, row, scan, predicate, range, capability, guarantee, transaction, and storage-diagnostics contracts. | This is the current home for DelosDB storage-provider contracts, not `delosdb-runtime-api`. No relocation in 23A. | Should DelosDB-owned storage contracts eventually move to an `io.github.ggeorg.delosdb.storage.api` package after consumers stabilize? |
| `delosdb-spi` | DelosDB-owned SPI. | Extension/provider SPI for storage, versioned storage, index, function, and type providers. | Keep as SPI, distinct from inherited runtime/store contracts and engine diagnostics. | Does it overlap with `delosdb-storage-api`, or is one engine-facing and the other provider-facing? |
| `delosdb-engine` / `io.github.ggeorg.delosdb.engine.trace` | DelosDB-owned engine model/diagnostic vocabulary. | Proven trace events, formatter, summary, lifecycle/storage/transaction concepts, and no-op sink/registry. | Keep engine-owned. Do not promote to API/SPI yet. Do not move to `delosdb-runtime-api`. | If a real diagnostics module, tool, or external researcher-facing report needs it, should this become `delosdb-engine-model` or `delosdb-diagnostics-api`? |
| `delosdb-engine` / `org.apache.derby.*` | Inherited Derby engine substrate plus DelosDB hooks. | SQL compilation/execution, JDBC, catalog, dictionary, execution rows, and inherited engine internals. | Keep Derby package traceability. DelosDB hooks should stay small and observable. | Which concepts become future model documentation rather than source moves? |
| `delosdb-storage-derby` | Inherited Derby storage implementation. | Heap/raw/btree provider implementation. | Keep as implementation, not API. Its stale JPMS scaffold is removed/ignored so the module remains classpath-compiled. | Which provider hooks should remain inherited versus move behind DelosDB storage contracts? |
| `delosdb-storage-mvcc` | DelosDB-native implementation. | Native MVCC storage provider, row/version storage, transaction state, persistence, and recovery-related internals. | Keep implementation-internal until Phase 24 exposes MVCC research observations. | Which MVCC concepts become diagnostics: snapshot, visible rows, WAL position, checkpoint, vacuum horizon? |
| `delosdb-storage-io` | DelosDB-owned storage implementation support. | Page and volume primitives. | Keep as implementation/support code, not a general runtime API. | Does this become the low-level storage foundation for MVCC and future providers? |
| `delosdb-storage-bridge` | Compatibility adapter. | Temporary Derby access-method compatibility bridge. | Do not treat as architecture authority. It can prove seams but should not define final ownership. | Which bridge responsibilities disappear when provider contracts are hardened? |
| `delosdb-storeless` | Test/demo implementation. | Storeless provider/proof surface. | Not a contract owner. | Keep only if it continues to prove provider independence. |
| `delosdb-client`, `delosdb-server`, `delosdb-tools`, `delosdb-optionaltools`, `delosdb-runner` | Inherited Derby user-facing/runtime modules. | Client driver, network server, tools, optional tools, runner. | Not homes for DelosDB core contracts. | Only revisit when packaging or public distribution requires it. |
| `delosdb-commons` | Inherited Derby shared/common substrate. | Shared Derby common errors, references, security, DRDA, utilities, and info packages. | Keep as shared inherited substrate. | Avoid duplicating common runtime concepts in new DelosDB modules. |
| `delosdb-tests` | Verification surface. | Focused DelosDB proofs plus inherited Derby tests. | Tests may consume qualified internal exports, but test access alone does not make a public API. | Which proof outputs should become examples or docs? |

## First-pass decisions

### D23A-1 — `delosdb-runtime-api` is not the home for all DelosDB contracts

`delosdb-runtime-api` currently exports inherited Derby runtime/service contracts. Its current role is
substrate extraction, not DelosDB model ownership. Mixing Module 21/22 model/diagnostic vocabulary
into it would make both roles less honest.

### D23A-2 — `engine.trace` remains engine-owned

The trace vocabulary was deliberately collapsed into `io.github.ggeorg.delosdb.engine.trace` and not
promoted to API/SPI. It remains the right location until a real production module outside the engine
needs to consume the same model.

### D23A-3 — storage contracts belong in the storage lane

Provider-neutral storage contracts belong in `delosdb-storage-api` or a future storage API package,
not in `delosdb-runtime-api`. Runtime services and storage-provider contracts are different layers.

### D23A-4 — Derby-origin contracts keep Derby traceability

Inherited Derby contracts should keep their Derby package identity unless a specific, proven DelosDB
replacement exists. Renaming inherited packages would make comparison with upstream Derby harder and
would not by itself create a better architecture.

### D23A-5 — future modules require real consumers

Candidate modules such as `delosdb-engine-model`, `delosdb-diagnostics-api`, or a renamed runtime
substrate module should be considered only when the dependency graph shows real production consumers
and the new module would remove coupling rather than create a placeholder.

## Candidate follow-up questions for Module 23B

The next Phase 23 pass should be a decision review, not a source move by default:

```text
Is delosdb-runtime-api honestly named, or should it later become a Derby-runtime-substrate module?
Is delosdb-storage-api correctly packaged, or only correctly modularized for now?
Does delosdb-spi overlap with delosdb-storage-api, or do they serve different consumers?
Should engine.trace remain engine-internal for another phase?
Which contract movements are actually required by production dependencies?
```

## Verification expectation

This document is intentionally documentation-only. The normal verification lane should remain:

```bash
./gradlew clean
./gradlew roadmapVerification
./scripts/module-dependency-tree.py
```

A green result means the ownership map was added without changing build, execution, storage,
optimizer, MVCC, or diagnostics behavior.
