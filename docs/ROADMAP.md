# DelosDB Roadmap

DelosDB is a Java-native, Derby-compatible database platform for building and
researching database capabilities against a real SQL engine.

North star:

```text
A Java developer can implement a new database capability — an index type,
storage model, function, type, or cost model — and run it against a real SQL
engine quickly, while DelosDB opens and improves the inherited Derby engine
where the existing seams are too narrow.
```

## Current rule

Do not start another provider family until the already-open provider seams are
finished, verified, or explicitly frozen at their current depth.

Workspace metadata is not a cleanup target. Local snapshots may contain `.git/`,
`.gradle/`, and `.idea/`. These directories must be ignored during review and
must never be deleted by cleanup scripts. Overlay ZIPs produced for DelosDB must
exclude workspace metadata, but the developer workspace ZIP workflow remains
unchanged.

## Finished seams

### CostModelProvider v2

Status: finished seam, green locally.

The active native path is:

```text
RAMTransaction.openStoreCost()
  -> StoreCostControllerBridge
  -> CostModelProviderResolver
  -> CostModelProvider
```

Proven implementations:

```text
factory id 0 -> heap CostModelProvider
factory id 1 -> btree CostModelProvider
```

The old `FromBaseTable` / `IndexProviderCostBridge` path is now legacy
optimizer-side diagnostic history. It may expose a provider estimate for review,
but it must not mutate planner cost. The native consumption path is the
`StoreCostController` adapter.

Remaining known boundary: `CostModelEstimate.startupCost()` is captured and
validated, but Derby's `StoreCostResult` cannot propagate startup cost yet. Only
total cost and estimated row count are consumed through this store-cost seam.

### IndexProvider v2

Status: finished abstraction proof, green locally.

Proven implementations:

```text
index btree  -> Derby-compatible SQL-backed index provider
index memory -> provider-owned in-memory index operations proof
```

`btree` remains the only SQL-creatable index provider. `memory` is intentionally
visible in the registry but rejected by `CREATE INDEX ... USING memory` until a
real Derby executor/storage bridge exists. The v2 proof is that the SPI is not a
single-provider facade: the second provider owns insert, delete, equality lookup,
range lookup, full scan, truncate, and row-count estimation behavior in its own
runtime proof.

## Shallow seams deliberately frozen

These provider families are useful metadata surfaces today, but they are not
finished external plugin products:

- `StorageProvider`: keep at heap-only v0/v1 unless we are ready to build a real
  second storage implementation.
- `FunctionProvider`: keep as the built-in DelosDB function metadata/execution
  seam unless we are ready for external function loading.
- `TypeProvider`: keep metadata-only. Do not touch parser, binder, type system,
  or storage format until we intentionally start type-system work.

Do not start `RewriteRuleProvider`, `ExternalTableProvider`, or
`SecurityPolicyProvider` while these seams remain shallow.

## Current foundation

Status: active and green locally.

Green gates:

```bash
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

Broader gates:

```bash
./gradlew fullVerification
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

## Current product seams

Implemented and green:

- Derby-compatible SQL/JDBC baseline through Gradle.
- `derbyRuntimeSmoke` covering runtime/product smokes.
- inherited Derby lang/JDBC suite through `:delosdb-tests:runDerbyLangSuite`.
- `CostModelProvider` v2: heap and B-tree cost providers selected through the
  native `StoreCostController` bridge.
- `IndexProvider` v2: B-tree provider plus memory provider runtime proof.
- `StorageProvider` v0/v1 surface: `CREATE TABLE ... USING heap`, metadata,
  persistence, visibility, and provider-level capabilities without synthetic
  table metadata.
- `FunctionProvider` v0/v1 surface: built-in `APP.DELOS_VERSION()`, metadata,
  execution, and visibility.
- `TypeProvider` v0 surface: Derby built-in type metadata and
  `SYSCS_UTIL.DELOSDB_TYPES()` visibility.
- unified extension registry through `SYSCS_UTIL.DELOSDB_EXTENSIONS()`.
- centralized test baseline for DelosDB system routines in
  `DelosDbTestBaselines`.

## Book verification rule

No new book chapter should be added until existing cited chapters are checked.
Current trusted chapters:

- Chapter 3: source-checked optimizer/cost vocabulary.
- Chapter 6: source-checked heap/B-tree and factory-id behavior.
- Chapter 8: source-checked extension-platform chapter, updated through
  IndexProvider v2.
- Chapter 11: source-checked CostModelProvider v2 chapter.

Other chapters remain source-reading or lab drafts until verified.

## Next milestone

The next work is not a new provider. Choose one of these, in order:

1. finish any remaining cleanup required by the CostModelProvider and
   IndexProvider v2 proofs,
2. verify the next untrusted book chapter against source,
3. only then decide whether StorageProvider, FunctionProvider, or TypeProvider
   deserves a real v2 implementation.

## Cleanup policy before new features

Before adding more provider families or book chapters:

1. remove stale checkpoint documents instead of maintaining parallel history,
2. keep generated book artifacts out of source control,
3. never delete `.git/`, `.gradle/`, or `.idea/`,
4. mark every book chapter with a verification status,
5. verify book citations before presenting a chapter as evidence-backed,
6. reduce `RESOLVE` comments and legacy casts in focused batches,
7. prefer finishing one seam to starting another one.

## Explicitly out of scope for now

- distributed SQL
- HA / replication
- PostgreSQL wire protocol
- MySQL compatibility
- external plugin marketplace
- new provider families
- custom storage engine
- full JSON engine
- vector database behavior

These may become future projects, but they must not distract from finishing and
verifying the seams already opened.
