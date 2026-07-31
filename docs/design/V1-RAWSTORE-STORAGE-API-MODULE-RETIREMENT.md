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

Repository Integrity Stage 3 retired three unwired path-diagnostic contracts from the original Stage 7.3
set:

```text
DelosStorageAccessDecisionKind
DelosStorageAccessDecisionState
DelosStoragePathDiagnostic
```

The same cleanup campaign added one package-local validation utility, `DelosStorageText`, to consolidate
ten duplicate non-blank checks without introducing another module. The current inventory therefore
contains three Stage 8 runtime diagnostics and one repository-integrity validation utility, for 102
current shared contracts:

```text
101 originally migrated during Stage 7.3
 -3 unwired path-diagnostic contracts retired
 +3 Stage 8 runtime diagnostics contracts
 +1 repository-integrity validation utility
---
102 current shared contracts
```

`gradle/static-analysis/delosdb-store-type-contract-inventory.txt` is the authoritative inventory
manifest. Both the module-retirement gate and later closeout gates consume that single exact file set
instead of maintaining duplicated magic counts. The replay manifest remains test-only evidence under
`delosdb-tests`; it is not a supported runtime API.

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
delosdb-derby-store-api                     -> owns 102 current shared contracts
                                                 98 surviving Stage 7.3 contracts
                                                 3 Stage 8 runtime diagnostics contracts
                                                 1 repository-integrity validation utility
authoritative inventory manifest              -> gradle/static-analysis/delosdb-store-type-contract-inventory.txt
engine/optional-tools patch wiring          -> derby-store-api only
production runtime provider discovery       -> unchanged
module count before future Lucene module    -> 20
final module count after Lucene addition    -> 21
```

The Phase 8 source oracle was deleted after production closeout. Before deletion it compiled
against the complete shared contract closure from `delosdb-derby-store-api`; it does not reactivate the
retired provider facade.

## Permanent evidence

```text
delosStorageApiModuleRetirementStaticAnalysis
delosV1ModuleArchitectureStaticAnalysis
delosModuleDependencyBoundaryStaticAnalysis
delosRuntimeArtifactModelStaticAnalysis
```
