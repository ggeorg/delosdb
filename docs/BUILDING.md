# Building DelosDB

DelosDB is built with the checked-in Gradle Wrapper. The supported developer path is Java 25 through `./gradlew`.

Do not use a system `gradle` command for this repository; it may run an older Gradle runtime and fail on Java 25 class files.

## Requirements

- JDK 25
- The checked-in Gradle Wrapper

## Main gates

```bash
./gradlew clean
./gradlew build
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

## Storage closeout gates

```bash
bash ./scripts/delete-stale-storage-smoke-dbs.sh

./gradlew :delosdb-storage-io:compileDelosDbStorageIo \
          :delosdb-storage-mvcc:compileDelosDbStorageMvcc \
          :delosdb-storage-derby:compileLegacyDerbyStorage \
          storagePhaseO5FullProviderParityCloseoutSmoke \
          storagePhaseC7StabilizationSmoke
```

## Current storage rule

```text
inherited Derby heap/raw store:
  Derby-owned; do not force through delosdb-storage-io

Delos MVCC page-backed storage:
  may use delosdb-storage-io page-volume contracts

future Delos heap provider:
  may use delosdb-storage-io only after source-gated heap work proves the boundary
```

## Cleanup rule

Do not add one-off Gradle tasks for temporary phase proofs. Prefer existing smokes or module-local tests. Stale generated databases should be removed with:

```bash
bash ./scripts/delete-stale-storage-smoke-dbs.sh
```
