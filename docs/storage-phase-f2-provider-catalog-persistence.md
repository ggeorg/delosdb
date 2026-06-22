# Storage Phase F2.1 — Provider catalog persistence

F2 starts the clean-design catalog work required before native Derby execution
can route `delos_mvcc` tables without the transitional SQL bridge.

## Problem

Before F2.1, `storageProviderName` existed on `CreateTableNode` and
`TableDescriptor`, but it was not written to Derby's catalog.  A Delos-backed
table could carry provider identity in memory during the creating session, but a
reopened database reconstructed the descriptor as the default heap provider.

That blocks the Phase F native execution path because `ResultSetFactory` will
need to decide whether a table scan is Derby heap-backed or Delos-backed using
catalog metadata, not bridge-local state.

## F2.1 decision

Use a bounded `SYSTABLES` column, not a side extension table.

```text
SYSTABLES.STORAGEPROVIDER VARCHAR(128) NULL
```

Reason:

```text
- Descriptor reconstruction already flows through SYSTABLESRowFactory.
- A nullable column keeps provider identity on the same catalog row as the table.
- A side table would require extra descriptor reconstruction lookups and separate
  consistency handling.
```

## Implementation shape

```text
CREATE TABLE ... USING delos_mvcc
  -> CreateTableNode.storageProviderName
  -> CreateTableConstantAction
  -> DataDescriptorGenerator.newTableDescriptor(..., storageProviderName)
  -> SYSTABLESRowFactory.makeRow(...)
  -> SYSTABLES.STORAGEPROVIDER
```

Readback:

```text
SYSTABLESRowFactory.buildDescriptorBody(...)
  -> row.getColumn(SYSTABLES_STORAGEPROVIDER)
  -> DataDescriptorGenerator.newTableDescriptor(..., storageProviderName)
  -> TableDescriptor.normalizeStorageProviderName(...)
```

Default heap tables store `NULL` in the catalog column and reconstruct as
`TableDescriptor.DEFAULT_STORAGE_PROVIDER_NAME`.

## Proof

The F2.1 smoke creates the table through Derby `PreparedStatement` execution so
it reaches Derby's parser and constant-action path rather than
`VersionedStorageSqlBridge`. It then checks both the catalog row and descriptor
metadata before and after database restart.

```text
storage-phase-f2-provider-catalog-db
  CREATE TABLE F2_PROVIDER_CATALOG (...) USING delos_mvcc
  CREATE TABLE F2_PROVIDER_DEFAULT (...)
  SELECT STORAGEPROVIDER FROM SYS.SYSTABLES ...
  shutdown / reopen
  TableStorageMetadataResolver.resolve(...)
```

## Next step

F2.2 should harden provider validation and prepare the metadata lookup required
by F3:

```text
tableName + activation
  -> LanguageConnectionContext
  -> DataDictionary
  -> TableDescriptor.storageProviderName
```

Do not start `GenericResultSetFactory` branching until that lookup boundary is
proven.
