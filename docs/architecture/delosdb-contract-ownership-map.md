# DelosDB contract ownership map

## Purpose

This document is the canonical Module 23 ownership map for visible contract and API surfaces in the
current DelosDB module graph. It classifies ownership and placement pressure only. It does not make
a boundary decision by itself; the decision record lives in
`docs/architecture/delosdb-contract-boundary-audit.md`.

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
| DelosDB implementation internals | Native MVCC/page/storage-IO implementation code. | Do not promote to API just because another package wants to observe it. |
| Compatibility adapters | Temporary code that adapts inherited Derby access paths to DelosDB concepts. | Treat as implementation scaffolding, not a long-term contract home. |

## Current module ownership map

| Module / surface | Ownership | Current role | Current home is honest? | Future pressure |
| --- | --- | --- | --- | --- |
| `delosdb-runtime-api` | Inherited Derby substrate, even though packaged as a DelosDB Gradle module. | Low-level `org.apache.derby.iapi.services.*`, `org.apache.derby.iapi.util`, `org.apache.derby.iapi.xml`, and `org.apache.derby.io` runtime/service contracts. | Yes, as Derby runtime/service substrate. It is not the home for all DelosDB APIs. | Possible future rename or split only if the dependency graph justifies the churn. |
| `delosdb-derby-store-api` | Inherited Derby store substrate. | Derby store/access/raw/btree contract lane used by inherited storage and engine paths. | Yes. | Identify later which store interfaces are true substrate versus provider implementation detail. |
| `delosdb-storage-api` | DelosDB-owned storage contract lane, currently anchored in Derby `org.apache.derby.iapi.store.types` package space for integration. | Provider-neutral table, row, scan, predicate, range, capability, guarantee, transaction, and storage-diagnostics contracts. | Yes, with a known package compromise. | Future package migration to `io.github.ggeorg.delosdb.storage.api` only after consumers stabilize. |
| `delosdb-spi` | DelosDB-owned SPI. | Extension/provider SPI for storage, versioned storage, index, function, and type providers. | Mostly yes. | Review overlap with `delosdb-storage-api` only when duplicated responsibilities are concrete. |
| `delosdb-engine` / `io.github.ggeorg.delosdb.engine.trace` | DelosDB-owned engine model/diagnostic vocabulary. | Proven trace events, formatter, summary, lifecycle/storage/transaction concepts, and no-op sink/registry. | Yes. It is engine-owned and not public API/SPI. | Promote only when a real production module outside the engine needs it. |
| `delosdb-engine` / `org.apache.derby.*` | Inherited Derby engine substrate plus DelosDB hooks. | SQL compilation/execution, JDBC, catalog, dictionary, execution rows, and inherited engine internals. | Yes. | Continue to prefer small observable hooks over package churn. |
| `delosdb-storage-derby` | Inherited Derby storage implementation. | Heap/raw/btree provider implementation. | Yes. | Later decide which common btree/sort/raw pieces are shared substrate versus Derby-provider implementation. |
| `delosdb-storage-mvcc` | DelosDB-native implementation. | Native MVCC storage provider, row/version storage, transaction state, persistence, and recovery-related internals. | Yes. | Phase 24 decides which MVCC concepts become observations. |
| `delosdb-storage-io` | DelosDB-owned storage implementation support. | Page and volume primitives. | Yes. | May become a stronger low-level storage foundation if MVCC and future providers converge on it. |
| `delosdb-storage-bridge` | Compatibility adapter. | Temporary Derby access-method compatibility bridge. | Yes, only as temporary adapter code. | Must shrink or retire as provider contracts harden. It must not become shared architecture. |
| `delosdb-storeless` | Test/demo implementation. | Storeless provider/proof surface. | Yes, if it keeps proving provider independence. | Remove if it stops proving useful behavior. |
| `delosdb-client`, `delosdb-server`, `delosdb-tools`, `delosdb-optionaltools`, `delosdb-runner` | Inherited Derby user-facing/runtime modules. | Client driver, network server, tools, optional tools, runner. | Yes. | Revisit only for packaging or distribution requirements. |
| `delosdb-commons` | Inherited Derby shared/common substrate. | Shared Derby common errors, references, security, DRDA, utilities, and info packages. | Yes. | Avoid duplicating common runtime concepts in new DelosDB modules. |
| `delosdb-tests` | Verification surface. | Focused DelosDB proofs plus inherited Derby tests. | Yes. | Test access alone does not make a public API. |

## Reading the map

### `delosdb-runtime-api` is not the home for all DelosDB contracts

`delosdb-runtime-api` currently exports inherited Derby runtime/service contracts. Its current role is
substrate extraction, not DelosDB model ownership. Mixing Module 21/22 model/diagnostic vocabulary
into it would make both roles less honest.

### `engine.trace` remains engine-owned

The trace vocabulary was deliberately collapsed into `io.github.ggeorg.delosdb.engine.trace` and not
promoted to API/SPI. It remains the right location until a real production module outside the engine
needs to consume the same model.

### Storage contracts belong in the storage lane

Provider-neutral storage contracts belong in `delosdb-storage-api` or a future storage API package,
not in `delosdb-runtime-api`. Runtime services and storage-provider contracts are different layers.

Existing DelosDB storage contracts may remain in Derby package space while Derby integration requires
it. New pure DelosDB contracts should not automatically be added under Derby packages unless the
integration point proves that package placement is necessary.

### Derby-origin contracts keep Derby traceability

Inherited Derby contracts should keep their Derby package identity unless a specific, proven DelosDB
replacement exists. Renaming inherited packages would make comparison with upstream Derby harder and
would not by itself create a better architecture.

### The bridge is not a contract owner

`delosdb-storage-bridge` is compatibility scaffolding. It may adapt Derby execution to provider
experiments, but it must not own common storage contracts, shared btree/sort code, or the final
provider architecture.

The desired direction remains:

```text
                         delosdb-storage-api
                           ↑              ↑
                           |              |
              delosdb-storage-derby   delosdb-storage-mvcc
```

not:

```text
engine -> storage-derby -> storage-bridge -> storage-mvcc
```

### Future modules require real consumers

Candidate modules such as `delosdb-engine-model`, `delosdb-diagnostics-api`, or a renamed runtime
substrate module should be considered only when the dependency graph shows real production consumers
and the new module would remove coupling rather than create a placeholder.

## Relationship to the boundary audit

This map classifies ownership. The boundary audit records the Module 23 decision:

```text
No Module 23 source move is justified by the current evidence.
```

Future layout work should be driven by new production consumers or by Phase 24 MVCC observations,
not by conceptual neatness alone.

## Verification expectation

This document is intentionally documentation-only. The normal verification lane should remain:

```bash
./gradlew clean
./gradlew roadmapVerification
./scripts/module-dependency-tree.py
```

A green result means the ownership map was tightened without changing build, execution, storage,
optimizer, MVCC, or diagnostics behavior.
