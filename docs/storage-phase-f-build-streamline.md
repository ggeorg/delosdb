# Storage Phase F build-script streamline

Phase F is closed. The executable proof value is now in the Phase F smoke tests,
not in the old per-step report guards that checked exact wording across source,
ROADMAP, and phase documents.

## What changed

The root build now applies one Phase F script:

```text
apply from: file('gradle/storage-phase-f-native-execution.gradle')
```

That script keeps the public verification task names and smoke coverage:

```text
verifyStoragePhaseF0F1aNativeStart
verifyStoragePhaseF2ProviderCatalogPersistence
verifyStoragePhaseF2ResultSetMetadataLookup
verifyStoragePhaseF3FactoryBranchProof
verifyStoragePhaseF3DelosTableScanSkeleton
verifyStoragePhaseF4NativeSelectEquality
verifyStoragePhaseF5NativeInsert
verifyStoragePhaseF6NativeDeleteEquality
verifyStoragePhaseF7NativeUpdateEquality
verifyStoragePhaseF8BridgeBypass
verifyStoragePhaseFConsolidation
```

## What was removed

The retired Phase F scripts were report guards. They repeatedly checked brittle
text tokens such as ROADMAP wording and constructor spelling. Those checks caused
several post-green failures while the actual smoke proofs were already healthy.

The cleanup script removes those retired guard files explicitly:

```text
scripts/delete-stale-phase-f-build-guards.sh
```

It uses only named `rm -f` entries. It does not remove source files, smoke tests,
or user files.

## What stays

The Phase F smokes stay in place. `verifyStoragePhaseFConsolidation` still runs
through the F8 native execution chain, which pulls in F0/F1a through F7 in order.

The normal stale database cleanup stays separate:

```text
scripts/delete-stale-storage-smoke-dbs.sh
```

## Next phase

G1 starts after this build cleanup slice and extends native predicate coverage
for:

```text
>, >=, <, <=
```
