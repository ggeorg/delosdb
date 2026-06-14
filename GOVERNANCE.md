# Governance

DelosDB is currently a maintainer-led fork.

## Decision principles

1. Derby-compatible behavior before novelty.
2. Working proofs before new architecture.
3. Source citations before book claims.
4. Finished seams before new provider families.
5. Benchmarks before performance claims.
6. Clear attribution to Apache Derby.

## Current project rule

No new provider family should be opened until the current finished seams remain green and the next planned seam has a concrete proof target.

Finished seams today:

- `CostModelProvider` v2: heap and B-tree through Derby's native store-cost seam.
- `IndexProvider` v2: B-tree SQL-backed provider plus memory provider-owned runtime proof.

Frozen shallow seams today:

- `StorageProvider`: heap-only.
- `FunctionProvider`: built-in DelosDB function only.
- `TypeProvider`: metadata-only.

## Release rule

No release should be cut unless these gates are green:

```bash
./gradlew fullVerification
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

Release notes must describe DelosDB changes, not inherited Apache Derby release-state files.
