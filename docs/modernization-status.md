# DelosDB Modernization Status

Last updated: 2026-06-14

DelosDB is a Gradle-only Java 21 modernization fork of Apache Derby with a
Derby-compatible SQL/JDBC baseline and a controlled DelosDB extension surface.
The current priority is completion, verification, and cleanup — not expansion.

Workspace metadata such as `.git/`, `.gradle/`, and `.idea/` is valid local
state and may appear in developer ZIP snapshots. Cleanup scripts must not delete
it.

## Current verification gates

```bash
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

Broader checks:

```bash
./gradlew fullVerification
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

## Finished provider seams

### CostModelProvider v2

Green locally. The native cost path is resolver-driven through
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

## Frozen shallow seams

- `StorageProvider`: heap-only provider surface; no second storage engine yet.
- `FunctionProvider`: built-in DelosDB function surface; no external function
  loading yet.
- `TypeProvider`: metadata-only type visibility; no parser, binder, or storage
  changes yet.

## Current product state

Green locally:

- runtime/product smokes through `derbyRuntimeSmoke`;
- inherited Derby language suite through `:delosdb-tests:runDerbyLangSuite`;
- CostModelProvider v2 through heap and B-tree store-cost providers;
- IndexProvider v2 through B-tree and memory providers;
- StorageProvider syntax/metadata/visibility for heap;
- FunctionProvider metadata/execution/visibility for built-in DelosDB function;
- TypeProvider metadata and SQL visibility;
- unified extension registry through `DELOSDB_EXTENSIONS()`;
- type metadata visibility through `DELOSDB_TYPES()`;
- system-routine permission baseline centralized in `DelosDbTestBaselines`.

## Current book state

Chapters 1--11 are source-checked for the claims they currently make. Future
edits must keep chapter verification-status paragraphs and evidence maps aligned
with source changes.

## Current inherited-code static analysis

The regenerated inherited-code summary is maintained in
`docs/inherited-code-static-analysis.md`. It records the current Derby-vs-DelosDB
source delta, the modernization areas completed in the inherited engine, and the
remaining algorithmic areas that must stay conservative.

## Current cleanup priority

Before adding features:

1. keep the root project layout modern: supported modules, `dev/`, `docs/`, `bin/`, and `tools/java/` only for required checked-in build jars;
2. remove stale inherited Derby web/release artifacts and old Ant/release helper folders through `scripts/remove-checkpoint-docs.sh`;
3. keep generated LaTeX/PDF build outputs out of source control;
4. never delete local `.git/`, `.gradle/`, or `.idea/`;
5. reduce inherited `RESOLVE` comments in focused batches;
6. reduce `instanceof`-then-cast patterns only where ownership and behavior are clear;
7. avoid opening a new provider family.
