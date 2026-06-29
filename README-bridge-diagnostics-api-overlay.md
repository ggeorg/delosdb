# Bridge diagnostics API overlay

This overlay removes the old public static `*ForTesting` diagnostics backdoors from the Derby MVCC bridge classes now that diagnostics are exposed through the storage-api diagnostics service.

## Why

The active diagnostic boundary is now:

```text
DelosStorageDiagnosticsRegistry.mvcc()
  -> DelosStorageDiagnostics
  -> MvccStorageDiagnostics
```

The old direct bridge calls were left as deprecated compatibility shims:

```text
MvccConglomerate.*ForTesting(...)
MvccConglomerateController.*ForTesting(...)
MvccScanController.*ForTesting(...)
```

Those methods made test/debug state look like part of the production bridge API. This overlay removes those public shims while keeping the package-private `*ForDiagnostics` methods used by `MvccStorageDiagnostics`.

## Changed files

```text
delosdb-storage-bridge/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccConglomerate.java
delosdb-storage-bridge/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccConglomerateController.java
delosdb-storage-bridge/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccScanController.java
```

## Apply

From the repo root:

```sh
unzip -oq ~/Downloads/delosdb-bridge-diagnostics-api-overlay.zip
```

No cleanup script is required.

## Verify

Run the full verification:

```sh
./gradlew clean fullVerification :delosdb-storage-mvcc:check
```

Optional focused checks:

```sh
./gradlew :delosdb-storage-bridge:compileJava :delosdb-engine:derbyJar

grep -RIn 'clearStatesForTesting\|resetInsertCountForTesting\|resetOpenCountForTesting\|candidateIndexLookupCountForTesting' \
  delosdb-storage-bridge/src/main/java/org/apache/derby/impl/store/access/mvcc
```

Expected grep result: only diagnostics-service methods and package-private diagnostic helpers should remain; the old public bridge test shims should be gone.

## Commit comment

```text
Remove deprecated MVCC bridge testing diagnostics shims
```
