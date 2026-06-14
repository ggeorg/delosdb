# DelosDB Modernization Status

Last updated: 2026-06-14

DelosDB is a Gradle-only Java 21 modernization fork of Apache Derby with a
Derby-compatible SQL/JDBC baseline and a controlled extension platform.

The current priority is completion and verification, not expansion. Workspace
metadata such as `.git/`, `.gradle/`, and `.idea/` is valid local state, may
appear in developer ZIP snapshots, and must not be deleted by cleanup scripts.

## Current verification gates

Use these local gates for current product work:

```bash
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

For broader release checks:

```bash
./gradlew fullVerification
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

## Finished seams

### CostModelProvider v2

Green locally. The native cost path is now resolver-driven through
`StoreCostControllerBridge` and supports two built-in providers:

```text
heap  -> factory id 0
btree -> factory id 1
```

The old `FromBaseTable` / `IndexProviderCostBridge` path is legacy diagnostic
only. It must not consume or mutate planner cost.

### IndexProvider v2

Green locally. The index provider abstraction has two built-in implementations:

```text
btree  -> Derby-compatible SQL-backed provider
memory -> provider-owned runtime proof
```

`CREATE INDEX ... USING memory` remains intentionally rejected until DelosDB
builds a real Derby executor/storage bridge for non-B-tree physical indexes.

## Shallow seams intentionally held

- `StorageProvider`: heap-only provider surface; no second storage engine yet.
- `FunctionProvider`: built-in DelosDB function surface; no external function
  loading yet.
- `TypeProvider`: metadata-only type visibility; no parser, binder, or storage
  changes yet.

No new provider family should be added while these existing seams remain shallow.

## Current product seams

Green locally:

- Derby runtime/product smokes through `derbyRuntimeSmoke`.
- inherited Derby lang/JDBC suite through Gradle.
- `CostModelProvider` v2 through heap and B-tree store-cost providers.
- `IndexProvider` v2 through B-tree and memory providers.
- `StorageProvider` v0/v1 surface.
- `FunctionProvider` v0/v1 surface.
- `TypeProvider` v0 metadata and SQL visibility.
- unified extension registry through `SYSCS_UTIL.DELOSDB_EXTENSIONS()`.
- type metadata visibility through `SYSCS_UTIL.DELOSDB_TYPES()`.
- system-routine permission test baseline centralized in `DelosDbTestBaselines`.

## Current modernization status

Completed modernization work includes:

- Java 21 Gradle-only build path.
- runtime jar verification and Maven Local publication checks.
- binary distribution foundation.
- inherited Derby message/resource generation through Gradle.
- inherited Derby lang/JDBC suite on the Gradle classpath.
- production modernization audit script.
- benchmark baseline script.
- selected Java 21 cleanup batches for finalizers, diagnostics, collection usage,
  timers, logging, and test activation.

## Current book status

Source-checked chapters:

- Chapters 1--11 are source-checked for the claims they currently make.
- Chapter 5 now covers the access-manager/storage-engine boundary with
  source-backed evidence.

Future edits must keep chapter verification-status paragraphs accurate and update
evidence maps when source claims change.

## Current cleanup priority

Before adding features:

1. remove stale checkpoint documents with `scripts/remove-checkpoint-docs.sh`,
2. keep generated LaTeX/PDF build outputs out of source control,
3. never delete local `.git/`, `.gradle/`, or `.idea/`,
4. verify book source citations chapter by chapter before treating the manuscript
   as reliable,
5. reduce inherited `RESOLVE` comments in focused batches,
6. reduce `instanceof`-then-cast patterns only where ownership and behavior are
   clear,
7. avoid opening a new provider family.
