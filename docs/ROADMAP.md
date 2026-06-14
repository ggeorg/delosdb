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

Do not start another provider family until one provider seam reaches a real v2
proof.

For the current line of work, that seam is `CostModelProvider`:

```text
RAMTransaction.openStoreCost()
  -> StoreCostControllerBridge
  -> CostModelProvider
  -> diagnostic / enabled proof
```

The next product milestone is not another v0 surface. It is a second real cost
model implementation, reached through the same store-cost path, proving that the
provider abstraction is not only a renamed B-tree path.

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
- `IndexProvider` v0/v1 surface: `CREATE INDEX ... USING btree`, metadata,
  registry visibility, provider-cost diagnostics, and the factory-id registry
  proof needed for future non-B-tree access methods.
- `StorageProvider` v0 surface: `CREATE TABLE ... USING heap`, metadata,
  persistence, visibility, and provider-level capabilities without synthetic
  table metadata.
- `FunctionProvider` v0 surface: built-in `APP.DELOS_VERSION()`, metadata,
  execution, and visibility.
- `CostModelProvider` v1 surface: native `StoreCostController` adapter with
  diagnostic and enabled modes.
- `TypeProvider` v0 surface: Derby built-in type metadata and
  `SYSCS_UTIL.DELOSDB_TYPES()` visibility.
- unified extension registry through `SYSCS_UTIL.DELOSDB_EXTENSIONS()`.
- centralized test baseline for DelosDB system routines in
  `DelosDbTestBaselines`.

None of these are complete external plugin products yet. They are controlled
engine seams. A seam is complete only after a second real implementation proves
that the abstraction is independent of the built-in Derby path.

## Next milestone: finish the cost seam

Goal: turn `CostModelProvider` from v1 to v2.

Required proof:

```text
factory id 0 -> heap CostModelProvider
factory id 1 -> btree CostModelProvider
```

The v2 gate should prove:

1. two provider implementations are registered,
2. the store-cost bridge resolves providers through the registry/resolver, not a
   hardcoded B-tree check,
3. heap and B-tree scans produce distinct provider probes,
4. diagnostic mode records Derby and provider estimates without changing Derby's
   result,
5. enabled mode consumes safe provider estimates,
6. the inherited Derby language suite remains green.

Only after this is green should Chapter 11 be updated.

## Cleanup policy before new features

Before adding more provider families or book chapters:

1. remove stale checkpoint documents instead of maintaining parallel history,
2. keep generated book artifacts out of source control,
3. verify book citations before presenting a chapter as evidence-backed,
4. reduce `RESOLVE` comments and legacy casts in focused batches,
5. keep existing docs short and current instead of adding new tracking files.

## Explicitly out of scope for now

- distributed SQL
- HA / replication
- PostgreSQL wire protocol
- MySQL compatibility
- external plugin marketplace
- custom physical index implementation beyond the next focused proof
- custom storage engine
- full JSON engine
- vector database behavior

These may become future projects, but they must not distract from finishing one
provider seam to v2.
