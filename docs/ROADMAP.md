# DelosDB Roadmap

DelosDB is a Java-native, Derby-compatible database platform for building and
researching database capabilities against a real SQL engine.

North star:

```text
A Java developer can implement a new database capability — an index type,
storage model, function, type, or cost model — and run it against a real SQL
engine, while DelosDB opens and improves the inherited Derby engine where the
existing seams are too narrow.
```

## Current rule

Finish existing seams before opening new ones.

Do not start `RewriteRuleProvider`, `ExternalTableProvider`,
`SecurityPolicyProvider`, or new `TypeProvider` semantics while the existing
provider surfaces still need hardening.

Workspace metadata is not a cleanup target. Local workspace ZIP snapshots may
contain `.git/`, `.gradle/`, and `.idea/`. Reviewers must ignore those
directories. Cleanup scripts must never delete them.

## Finished seams

### CostModelProvider v2

Status: finished seam, green locally.

Active native path:

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

The old `FromBaseTable` / `IndexProviderCostBridge` path is legacy
optimizer-side diagnostic history only. Native provider-cost consumption belongs
to the store-cost adapter.

Known boundary: `CostModelEstimate.startupCost()` is captured and validated, but
Derby's `StoreCostResult` can propagate only total cost and estimated row count.

### IndexProvider v2

Status: finished abstraction proof, green locally.

Proven implementations:

```text
index btree  -> Derby-compatible SQL-backed index provider
index memory -> provider-owned runtime operations proof
```

`btree` remains the only SQL-creatable index provider. `memory` is visible in the
registry and has its own runtime proof, but `CREATE INDEX ... USING memory` is
intentionally rejected until a real Derby executor/storage bridge exists.

## Shallow seams deliberately frozen

- `StorageProvider`: heap-only provider surface; no second storage engine yet.
- `FunctionProvider`: built-in DelosDB function surface; no external function
  loading yet.
- `TypeProvider`: metadata-only Derby type visibility; no parser, binder, type
  system, or storage-format changes yet.

## Current green gates

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

## Current product seams

Implemented and green locally:

- Derby-compatible SQL/JDBC baseline through Gradle.
- `CostModelProvider` v2 through heap and B-tree cost providers.
- `IndexProvider` v2 through B-tree and memory providers.
- `StorageProvider` heap-only surface with provider-level capabilities.
- `FunctionProvider` built-in DelosDB function surface.
- `TypeProvider` metadata-only SQL visibility.
- Unified extension registry through `SYSCS_UTIL.DELOSDB_EXTENSIONS()`.
- Type metadata visibility through `SYSCS_UTIL.DELOSDB_TYPES()`.
- DelosDB system-routine test baseline through `DelosDbTestBaselines`.

## Book verification rule

No new book chapter should be added until existing cited chapters stay
source-checked. Current status: Chapters 1--11 are source-checked for the claims
they currently make.

Future chapter edits must preserve each chapter's verification-status paragraph
and update its evidence map when source claims change.

## Next milestone options

Choose one focused track at a time:

1. reduce stale docs and duplicated status text;
2. reduce inherited `RESOLVE` comments in reviewed batches;
3. reduce `instanceof`-then-cast patterns where ownership is clear;
4. only then decide whether `StorageProvider`, `FunctionProvider`, or
   `TypeProvider` deserves a real v2 implementation.

## Explicitly out of scope for now

- distributed SQL;
- HA / replication;
- PostgreSQL wire protocol;
- MySQL compatibility;
- external plugin marketplace;
- new provider families;
- custom storage engine;
- full JSON/type-system work;
- vector database behavior.
