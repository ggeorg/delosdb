# DelosDB v1 RawStore storage-API module retirement

## Decision

Stage 7.3 removes the standalone `delosdb-storage-api` project after assigning each valid
responsibility to an existing final-target module. This is responsibility convergence, not package
renaming and not creation of another provider authority.

## Consumer classification

The module contained 128 Java sources. The migration classifies them as follows:

```text
101 shared inherited-store, typed-value, lifecycle, diagnostics, snapshot, and metadata contracts
    -> delosdb-derby-store-api

27 unused parallel table/index/mutation/provider-factory contracts
    -> deleted

6 unused DerbyStorage* facade implementation classes
    -> deleted from delosdb-storage-derby

neutral external index/search vocabulary
    -> already owned by delosdb-spi; duplicate storage facade types are not preserved
```

The moved 101 shared contracts retain the package `org.apache.derby.iapi.store.types` because they are part
of the inherited store/API patch seam consumed by engine, RawStore, MVCC, tests, and optional tools.
The move changes Gradle ownership, not Java semantics.

Stages 8.2 and 8.3 subsequently added three runtime diagnostics contracts to the same final owner:

```text
DelosRawStoreIoDiagnosticsDirectory
DelosRawStoreIoMetrics
DelosRawStoreIoSnapshot
```

The current shared-contract inventory is therefore 104: the original 101 Stage 7.3 migrations plus
three post-retirement runtime diagnostics contracts. The replay manifest is test-only evidence under
`delosdb-tests`; it is not a supported runtime API. This growth does not recreate the retired module
or a parallel provider API.

## Deleted parallel facade

The removed API surface represented an unused second table-access model: access contexts, generic
rows, predicates, projections, ranges, table capabilities, generic index access, and mutation
preparation/results. The corresponding `DerbyStorageProvider`, `DerbyStorageTable`,
`DerbyStorageTransaction`, `DerbyStorageScan`, row-adapter, and row-location facade implementations
had no active production consumer.

RawStore access-method APIs, `ExternalAccessMethodProvider`, and the database-owned transaction
lifecycle remain authoritative. No `DelosStorageProviderFactory` service registration is restored.

## Build and artifact result

```text
delosdb-storage-api project                 -> removed
delosdb-storage-api.jar                     -> removed
delosdb-derby-store-api                     -> owns 104 current shared contracts
                                                 101 migrated during Stage 7.3
                                                 3 post-retirement runtime contracts from Stage 8.2
engine/optional-tools patch wiring          -> derby-store-api only
production runtime provider discovery       -> unchanged
module count before future Lucene module    -> 20
final module count after Lucene addition    -> 21
```

The retained Phase 8 oracle remains quarantined in the `legacyRetained` source set. It compiles
against the complete shared contract closure from `delosdb-derby-store-api`; it does not reactivate the
retired provider facade.

## Permanent evidence

```text
delosStorageApiModuleRetirementStaticAnalysis
delosV1ModuleArchitectureStaticAnalysis
delosModuleDependencyBoundaryStaticAnalysis
delosRuntimeArtifactModelStaticAnalysis
```
