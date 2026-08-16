# DelosDB v1 module architecture

## Status

This document records the current v1 module graph and its permanent ownership boundaries.

## Current module state

The neutral boot and transaction-lifecycle seams live in `delosdb-derby-store-api`.
`delosdb-storage-derby` creates database-owned access-method context and owns transaction lifecycle
bracketing. The MVCC provider implementation lives in `delosdb-storage-mvcc`. The former
`delosdb-storage-bridge`, `delosdb-storage-io`, and `delosdb-storage-api` projects are retired.
Shared store/type contracts live in `delosdb-derby-store-api`; unused parallel facade contracts were
removed.

The permanent `delosV1ModuleArchitectureStaticAnalysis` gate enforces provider isolation and the
machine-readable target graph while remaining valid when optional provider modules are present.

## Architectural rules

```text
1. RawStore is the only physical and transactional storage authority.
2. Heap and MVCC are peer access methods.
3. Lucene is optional derived state.
4. The engine depends only on neutral APIs.
5. MVCC and Lucene do not compile against engine implementation classes.
6. Storage implementation modules do not depend upward on MVCC or Lucene.
7. Lucene types never appear in general DelosDB SPIs.
8. The Gradle graph must express these boundaries without output-directory backdoors.
```

## Final target modules

The target contains 21 Gradle subprojects:

```text
delosdb-osgi-stub
delosdb-commons
delosdb-runtime-api
delosdb-annotations
delosdb-spi

delosdb-derby-store-api
delosdb-storage-derby
delosdb-storage-mvcc
delosdb-search-lucene

delosdb-engine
delosdb-client
delosdb-tools
delosdb-runner
delosdb-optionaltools
delosdb-server

delosdb-tests
delosdb-pptesting
delosdb-storeless
delosdb-demos
delosdb-locales
delosdb-buildtools
```

Add:

```text
delosdb-search-lucene
```

Retired after responsibility absorption or quarantine convergence:

```text
delosdb-storage-api
delosdb-storage-bridge
delosdb-storage-io
```

## Dependency direction

```text
delosdb-runtime-api       delosdb-spi
          \                 /
           delosdb-derby-store-api
                 |
       +---------+---------+
       |                   |
delosdb-storage-derby  delosdb-engine
                           |
                   neutral discovery
                           |
              +------------+------------+
              |                         |
 delosdb-storage-mvcc       delosdb-search-lucene
```

Allowed production dependencies:

```text
delosdb-engine
    -> delosdb-runtime-api
    -> delosdb-derby-store-api
    -> delosdb-spi
    -> delosdb-commons
    -> delosdb-annotations

delosdb-storage-derby
    -> delosdb-runtime-api
    -> delosdb-derby-store-api
    -> delosdb-commons
    -> delosdb-annotations

delosdb-storage-mvcc
    -> delosdb-runtime-api
    -> delosdb-derby-store-api
    -> delosdb-spi
    -> delosdb-commons

delosdb-search-lucene
    -> delosdb-runtime-api
    -> delosdb-derby-store-api
    -> delosdb-spi
    -> delosdb-commons
    -> Lucene
```

Forbidden production dependencies:

```text
delosdb-engine          -X-> delosdb-storage-mvcc
delosdb-engine          -X-> delosdb-search-lucene
delosdb-storage-mvcc    -X-> delosdb-engine implementation
delosdb-storage-mvcc    -X-> delosdb-search-lucene
delosdb-search-lucene   -X-> delosdb-engine implementation
delosdb-storage-derby   -X-> delosdb-storage-mvcc
delosdb-storage-derby   -X-> delosdb-search-lucene
```


## Permanent architecture gates

The current migration is continuously checked by:

```text
delosModuleDependencyBoundaryStaticAnalysis
delosV1ModuleArchitectureStaticAnalysis
```

The second task reads:

```text
gradle/static-analysis/delosdb-v1-final-module-target.txt
```

and freezes:

```text
21 final subprojects
add delosdb-search-lucene
retire delosdb-storage-api, delosdb-storage-io, delosdb-storage-bridge
provider artifacts delosdb-storage-mvcc.jar and delosdb-search-lucene.jar
build-only delosdb-storage-derby.jar
forbidden engine/provider and RawStore/provider production edges
no Lucene implementation types in neutral APIs
```

The bridge is now removed. The gate requires `delosdb-storage-mvcc` to publish the sole neutral
access-method provider directly while the engine remains implementation-independent.

The `delosV1ModuleArchitectureStaticAnalysis` task is the current implementation of the proposed `verifyStorageAuthorityModuleGraph`
and migration-time `verifyProviderIsolation` contracts. Final closeout additionally requires strict
semantic equivalents of:

```text
verifyNoIndependentMvccIo
verifyNoIndependentMvccDurability
verifyNoLegacyLucene
verifyRetiredStorageModules
```

Those checks become removal gates only after their replacement implementations are green.

Public implementation record:

```text
docs/design/V1-MODULE-BOUNDARY-ENFORCEMENT.md
```

## Module responsibilities

### `delosdb-runtime-api`

Owns generic runtime and storage contracts such as the inherited `StorageFactory`,
`WritableStorageFactory`, `StorageFile`, and `StorageRandomAccessFile` boundaries.

Future JDK 25 contracts must remain generic and must not mention MVCC, Lucene, version chains,
full-text documents, or `DelosPageVolume`.

### `delosdb-spi`

Owns provider-neutral extension contracts for index identity, capabilities, lifecycle categories,
derived-index mutation, search requests/results, consistency modes, and states.

It does not expose Lucene, RawStore implementation, or MVCC implementation types.

### `delosdb-derby-store-api`

Owns the narrow shared contracts between engine access/transaction code, RawStore implementation,
heap, MVCC, and transactional derived-index journaling.

The exact transaction participant API is not frozen until the complete Derby transaction lifecycle
matrix is proved.

### `delosdb-storage-derby`

Becomes the sole physical database storage implementation owner:

```text
RawStore
heap
containers/pages
buffer management
WAL and undo
checkpoint and recovery
backup
file and memory storage
locking infrastructure
shared JDK 25 storage implementations
```

It may continue producing a build-time patch artifact whose classes are incorporated into
`derby.jar`; it is not advertised as an independent runtime store.

### `delosdb-storage-mvcc`

Becomes a RawStore peer access method. It owns MVCC semantics, not physical durability.

It owns the valid Derby-facing provider and adaptation classes formerly held by the bridge.
There is no production compile dependency on `delosdb-engine`. The provider patch artifact is consumed
only by the Derby-compatible jar assembly.

### `delosdb-search-lucene`

Owns Lucene-specific implementation, analyzer registration, query parsing, document encoding,
generation publication, journal replay, watermark reconciliation, rebuild, and diagnostics.

It does not own base data or transaction commit and does not depend on engine implementation classes.

### `delosdb-engine`

Owns SQL grammar, binding, optimization, catalog, execution, transaction integration, provider
neutral discovery, mutation dispatch, and search invocation.

It does not instantiate or compile against MVCC or Lucene implementation classes.

### `delosdb-optionaltools`

Retains non-Lucene tools. Legacy Lucene support and dependencies leave this module after the new
provider reaches parity.

## Retired modules

### `delosdb-storage-bridge`

Removed after its valid provider/registration glue moved into
`delosdb-storage-mvcc`.

### `delosdb-storage-io`

Removed after its retained-only page/volume sources were quarantined and then deleted by the final
source-retirement gate. `DelosPageVolume` does not survive in the working tree or as a production I/O authority.

### `delosdb-storage-api`

Removed after 101 shared contracts moved to `delosdb-derby-store-api`. Twenty-seven
unused parallel facade/provider-factory contracts and six unused Derby facade implementations were
deleted; neutral external-index vocabulary remains in `delosdb-spi`.

## Convergence result

The current module graph reflects completed storage ownership convergence:

```text
neutral provider seams                         -> delosdb-derby-store-api
RawStore-backed MVCC provider                  -> delosdb-storage-mvcc
legacy bridge / storage-io / storage-api       -> retired
optional search provider                       -> isolated provider module
module and runtime-artifact boundaries         -> permanently enforced
```

Retired modules were removed only after their replacement ownership passed the required recovery and
compatibility tests.

## Final runtime artifacts

```text
core:
    derby.jar
    derbyshared.jar
    derbytools.jar
    derbyclient.jar
    derbynet.jar

normal DelosDB distribution:
    delosdb-storage-mvcc.jar

optional:
    delosdb-search-lucene.jar
    derbyoptionaltools.jar
```

Not distributed as independent runtime artifacts:

```text
delosdb-storage-api.jar
delosdb-storage-io.jar
delosdb-storage-bridge.jar
delosdb-storage-derby.jar
```
