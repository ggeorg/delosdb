# Engine versioned-storage SPI cut overlay

This overlay is the second step after `delosdb-spi-quarantine-overlay`.

It removes the production engine's dependency on the quarantined `delosdb-versioned-storage-spi` module.

## What changes

- `delosdb-engine/build.gradle` no longer depends on `:delosdb-versioned-storage-spi`.
- `org.apache.derby.engine` no longer requires `io.github.ggeorg.delosdb.versioned.storage.spi`.
- Engine built-in extension metadata no longer has a `VERSIONED_STORAGE` provider family.
- SQL provider validation still recognizes reserved bridge names such as `delos_mvcc`, but does not use the old `VersionedStorageProvider` ServiceLoader path.
- Retired engine-side execution/prototype classes are removed by the cleanup script.

The active path remains:

```text
Derby SQL/store/access
  -> delosdb-storage-bridge
  -> DelosStorageProviderFactory
  -> delosdb-storage-mvcc
```

The old path remains quarantined outside the engine for MVCC module tests/proofs:

```text
delosdb-storage-mvcc
  -> delosdb-versioned-storage-spi
```

## Apply

From the repository root after applying the previous SPI quarantine overlay:

```sh
unzip -oq delosdb-engine-versioned-spi-cut-overlay.zip
./scripts/cleanup-overlay-engine-versioned-spi-cut.sh
```

## Verify

Run the full verification suite, not only focused tests:

```sh
./gradlew clean fullVerification :delosdb-storage-mvcc:check
```

Additional dependency check:

```sh
./gradlew :delosdb-engine:dependencies --configuration runtimeClasspath
```

Expected result: `delosdb-engine` should not list `delosdb-versioned-storage-spi` as a dependency.

Commit comment text:

```text
Remove production engine dependency on versioned storage SPI
```
