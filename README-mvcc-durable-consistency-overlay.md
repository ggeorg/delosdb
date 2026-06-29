# DelosDB MVCC durable consistency overlay

This overlay adds the first boot-time durable MVCC consistency contract.

## What changes

- Adds `MvccDurableConsistencyCheck`.
- Adds `PageBackedMvccTable.validateConsistency()`.
- Runs a consistency check during `PageBackedMvccTable.open(...)` after WAL recovery and row-directory reconciliation.
- Adds `MvccDurableConsistencyCheckTest` under the existing durable page-recovery proof task.

## Why

The active `delos_mvcc` page-backed path has pages, a page-mutation log, a row-directory sidecar, and checkpoint metadata. Before expanding features, boot must prove these agree:

- each row key has one stable row id,
- version ids are unique,
- previous-version pointers form one closed chain,
- the row-directory head points to the newest page version,
- tombstone state matches between the page head and row-directory head.

This is inspired by the consistency-check discipline in mature engines: make corruption and partial recovery observable immediately rather than silently accepting bad state.

## Apply

```sh
unzip -oq ~/Downloads/delosdb-mvcc-durable-consistency-overlay.zip
```

No cleanup script is needed.

## Verify

```sh
./gradlew clean fullVerification :delosdb-storage-mvcc:check
```

Focused check:

```sh
./gradlew :delosdb-storage-mvcc:runMvccPageRecoveryTest
```

## Commit comment

```text
Add durable MVCC consistency check
```
