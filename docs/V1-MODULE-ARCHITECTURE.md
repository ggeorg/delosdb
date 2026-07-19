# DelosDB v1 module architecture

## Status

This document records the final target module graph. It does not authorize immediate module deletion.
The current modules remain until their responsibilities have moved and replacement gates are green.


## Current migration state

The neutral boot and transaction-lifecycle seams now live in `delosdb-derby-store-api`.
`delosdb-storage-derby` creates database-owned access-method context and owns transaction lifecycle
bracketing. `delosdb-storage-bridge` consumes those neutral seams temporarily. This is a migration
condition, not a reason to retain the bridge module in the final graph.

Stage 2.3 adds a machine-readable final target and the permanent
`delosV1ModuleArchitectureStaticAnalysis` gate. The gate enforces current provider isolation while
remaining valid as the bridge is absorbed, the three legacy storage modules are retired, and
`delosdb-search-lucene` is added.

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

Retire only after absorption:

```text
delosdb-storage-api
delosdb-storage-io
delosdb-storage-bridge
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

During migration, the bridge is allowed only as the single neutral access-method provider owner. Once
it is removed, the gate requires `delosdb-storage-mvcc` to publish the provider directly.

The Stage 2.3 task is the current implementation of the proposed `verifyStorageAuthorityModuleGraph`
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

After a neutral provider seam exists, the valid Derby-facing bridge classes move into this module.
There is no production compile dependency on `delosdb-engine`.

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

Removed after its valid provider/registration glue moves into neutral APIs and
`delosdb-storage-mvcc`.

### `delosdb-storage-io`

Removed after useful implementation work is absorbed into the shared RawStore/storage
implementation. `DelosPageVolume` does not survive as a second public I/O authority.

### `delosdb-storage-api`

Removed after its valid contracts move to `delosdb-derby-store-api` and `delosdb-spi`, and its
parallel store/commit abstractions are deleted.

## Migration order

```text
1. design proofs
2. neutral provider seams
3. complete RawStore MVCC vertical slice
4. absorb bridge
5. converge remaining MVCC persistence
6. absorb shared storage API and I/O implementation work
7. modernize Lucene
8. enforce final module and runtime-artifact gates
```

No module is removed before its replacement compiles, runs, and passes the required recovery and
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
