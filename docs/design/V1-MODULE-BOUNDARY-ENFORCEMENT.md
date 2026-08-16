# DelosDB v1 module-boundary enforcement

## Decision

DelosDB enforces storage and provider ownership through the Gradle graph, source dependency checks,
runtime artifact metadata, and provider discovery. The current build has 20 active subprojects. The
retired storage bridge/API/I/O projects are absent.

## Current active boundary

```text
delosdb-engine
    -> neutral runtime/store/SPIs
    -X-> delosdb-storage-mvcc implementation

delosdb-storage-derby
    -> neutral runtime/store contracts
    -X-> delosdb-storage-mvcc implementation

delosdb-storage-mvcc
    -> neutral runtime/store/SPIs
    -X-> delosdb-engine implementation
```

The engine discovers the MVCC access method through the neutral
`ExternalAccessMethodProvider` contract. `delosdb-storage-mvcc` owns exactly one production provider
implementation and service entry.

## Retired modules

These projects are not part of the current build:

```text
delosdb-storage-api
delosdb-storage-bridge
delosdb-storage-io
```

Their former responsibilities were either absorbed into `delosdb-derby-store-api` and
`delosdb-storage-mvcc` or removed when the independent MVCC persistence stack was retired.

## Runtime packaging

The authoritative runtime-artifact model is `gradle/delosdb-runtime-artifacts.gradle`.

Current behavior is:

```text
derby.jar
    contains the engine plus the production MVCC provider implementation needed by the
    Derby-compatible runtime

delosdb-storage-mvcc.jar
    is assembled as the provider artifact
    is not simultaneously placed on the normal runtime classpath

delosdb-derby-store-api.jar
    is assembled support code

delosdb-storage-derby.jar
    is build-only
```

This avoids a production compile dependency from the engine to the MVCC project while also avoiding
split-package duplication at runtime.

## Checked target manifest

`gradle/static-analysis/delosdb-v1-final-module-target.txt` records both the current allowed graph and
a deferred optional search-provider boundary.

`delosdb-search-lucene` is listed there as deferred. It is not present in `settings.gradle`, is not a
current provider artifact, and is not a current product capability.

## Permanent verification

```text
delosModuleDependencyBoundaryStaticAnalysis
delosV1ModuleArchitectureStaticAnalysis
verifyDelosRuntimeStorageProviders
```

Together these checks verify:

```text
no unexpected Gradle modules
no missing non-deferred target modules
no forbidden production source dependency edges
no retired storage module on the active graph
one production MVCC provider registration
no retired external MVCC provider service
runtime jar composition consistent with the declared artifact model
```

## Scope

This module decision does not define SQL semantics, table formats, transaction visibility, locking,
vacuum behavior, or recovery policy. Those remain owned by their storage and transaction
implementations.
