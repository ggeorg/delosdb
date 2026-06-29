# Storage table contract overlay

This overlay is the first storage-api cleanup after the SPI quarantine work.

## Goal

Shrink the active `DelosStorageTable` contract so it contains only runtime row and
transaction operations used by the Derby bridge.

The previous interface mixed these concerns in one type:

- runtime row operations
- transaction lifecycle
- durable-state maintenance
- row-location lookup
- candidate-index lookup
- test/diagnostic file paths and counters
- vacuum outcomes

This overlay separates the non-core surfaces into capability interfaces while
keeping the active provider path unchanged.

## Changes

Adds new storage-api capability interfaces:

- `DelosStorageMaintenance`
- `DelosStorageRowLocator`
- `DelosStorageCandidateIndex`
- `DelosStorageTableDiagnostics`

Narrows `DelosStorageTable` to:

- `beginTransaction`
- `snapshot`
- `openScan`
- `read`
- `insert`
- `update`
- `delete`
- `commit`
- `abort`
- `nextRowId`
- `close`

Updates the active MVCC provider adapter:

- `MvccInheritedTable` now implements `DelosStorageTable` plus the capability interfaces.

Updates the Derby bridge state:

- `MvccConglomerateState` keeps `DelosStorageTable` for core operations.
- Maintenance, row-location, candidate-index, and diagnostic calls are routed through the new capability interfaces.
- Missing provider capabilities now fail early with a clear `IllegalStateException`.

## What this does not do

This does not rename packages or split `delosdb-storage-api` into multiple modules.
That remains intentionally delayed to avoid JPMS/package-split churn.

This does not change the active MVCC path:

```text
Derby store/access bridge
  -> DelosStorageProviderFactory
  -> delosdb-storage-mvcc
```

## Apply

From repo root:

```sh
unzip -oq ~/Downloads/delosdb-storage-table-contract-overlay.zip
```

No cleanup script is needed.

## Verify

Run the full verification:

```sh
./gradlew clean fullVerification :delosdb-storage-mvcc:check
```

Optional focused checks:

```sh
./gradlew :delosdb-storage-api:compileJava \
          :delosdb-storage-bridge:compileJava \
          :delosdb-storage-mvcc:compileJava
```

## Commit comment

```text
Split storage table capabilities from core table contract
```
