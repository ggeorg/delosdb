# DelosDB MVCC versioned-storage ServiceLoader cut overlay

This overlay performs the third SPI quarantine cut.

## Intent

The active Derby/MVCC storage path is now:

```text
Derby store/access bridge
  -> DelosStorageProviderFactory
  -> delosdb-storage-mvcc
```

The old `VersionedStorageProvider` execution SPI is quarantined for direct MVCC model/proof code only. The MVCC jar should no longer advertise that old SPI through `META-INF/services`.

## Changes

- Updates `delosdb-storage-mvcc/build.gradle` so `processResources` excludes:

```text
META-INF/services/io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider
```

- Adds `scripts/cleanup-overlay-mvcc-versioned-service-cut.sh` to remove the stale service file from the source tree.

## Apply

From repo root:

```sh
unzip -oq ~/Downloads/delosdb-mvcc-versioned-service-cut-overlay.zip
./scripts/cleanup-overlay-mvcc-versioned-service-cut.sh
```

## Verify

Run the full verification:

```sh
./gradlew clean fullVerification :delosdb-storage-mvcc:check
```

Optional focused check:

```sh
./gradlew clean :delosdb-storage-mvcc:jar
jar tf delosdb-storage-mvcc/build/libs/delosdb-storage-mvcc.jar | grep 'VersionedStorageProvider' || true
jar tf delosdb-storage-mvcc/build/libs/delosdb-storage-mvcc.jar | grep 'DelosStorageProviderFactory'
```

Expected:

```text
# no VersionedStorageProvider service entry
META-INF/services/org.apache.derby.iapi.store.types.DelosStorageProviderFactory
```

## Commit comment

```text
Stop advertising MVCC through versioned storage ServiceLoader
```
