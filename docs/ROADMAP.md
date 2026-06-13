# DelosDB Roadmap

DelosDB is a Java-native, Derby-compatible database platform for building and
researching database capabilities against a real SQL engine.

North star:

```text
A Java developer can implement a new database capability — an index type,
storage model, function, type, or cost model — and run it against a real SQL
engine quickly, without modifying thousands of lines of C.
```

## Current foundation

Status: active and green locally.

Completed product seams:

- Derby-compatible SQL/JDBC baseline through Gradle.
- `derbyRuntimeSmoke` covering runtime/product smokes.
- inherited Derby lang/JDBC suite through `:delosdb-tests:runDerbyLangSuite`.
- `IndexProvider` v0: `CREATE INDEX ... USING btree`, metadata, provider-cost hook, diagnostics.
- `StorageProvider` v0: `CREATE TABLE ... USING heap`, metadata, persistence, visibility.
- `FunctionProvider` v0: built-in `APP.DELOS_VERSION()`, metadata, execution, visibility.
- `ExtensionRegistry` v0: unified provider registry plus `SYSCS_UTIL.DELOSDB_EXTENSIONS()` SQL visibility.

## Pillar 1 — Compatibility

Goal: preserve a trustworthy Derby-compatible base while DelosDB adds extension
seams.

Primary gates:

```bash
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

Compatibility is not the product by itself; it is the safety net that makes the
extension platform credible.

## Pillar 2 — Extension Platform

Goal: make database capabilities explicit, inspectable, and provider-owned.

Current provider families:

- `IndexProvider`
- `StorageProvider`
- `FunctionProvider`

Near-term work:

1. Provider infrastructure hygiene: shared registry/resolver infrastructure.
2. Catalog provider metadata integrity: prove stored provider metadata is real, not default coincidence.
3. Extension platform demo: one coherent SQL walkthrough using table, index, function, and registry visibility.
4. `TypeProvider` v0 as a provider proof, not as a full JSON product feature.

## Pillar 3 — Planner Research

Goal: expose controlled optimizer hooks for research without breaking Derby's
planner correctness.

Current state:

- Index provider cost request/estimate bridge exists.
- Provider-cost consumption is opt-in.
- Planner cost diagnostics are visible and provider-neutral.

Next research direction:

- `CostModelProvider` v0.
- cardinality/cost estimate replacement experiments.
- fallback safety when provider estimates are missing or unsafe.
- diagnostics explaining planner decisions.

This is the strongest research path: a learned or experimental cost model should
be pluggable without rewriting the inherited optimizer.

## Pillar 4 — Storage Research

Goal: keep Derby heap storage as the trusted default while opening a path for
controlled storage experiments later.

Current state:

- Built-in `heap` provider is explicit.
- Table metadata records the selected storage provider.
- Custom storage engines are intentionally not supported yet.

Later candidates:

- in-memory provider for tests.
- LSM-style provider.
- columnar/append-only provider.
- disaggregated/remote storage experiments.

## Pillar 5 — Modern Types and Functions

Goal: support modern data capabilities through provider seams.

Near-term framing:

- JSON should be the first `TypeProvider` proof, not a claim of complete JSON support.
- JSON v0 may map safely to an existing Derby type until real semantics exist.
- no JSON operators, path indexes, or binary format until the type seam is proven.

## Explicitly out of scope for now

- distributed SQL
- HA / replication
- PostgreSQL wire protocol
- MySQL compatibility
- external plugin marketplace
- custom physical index implementation
- custom storage engine
- full JSON engine
- vector database behavior

These may become future projects, but they must not distract from the v0
extension platform.
