# DelosDB v1 module-boundary enforcement

## Status

The final target is frozen. The bridge, storage-io, and storage-api projects are retired after their
valid responsibilities moved into `delosdb-storage-mvcc` or `delosdb-derby-store-api`, and
deleting the unused parallel facade.

## Final target

The final graph contains 21 Gradle subprojects:

```text
current subprojects after storage-module retirement: 20
required new module: delosdb-search-lucene
retired: delosdb-storage-api, delosdb-storage-bridge, delosdb-storage-io
final subprojects: 21
```

The target is machine-readable in:

```text
gradle/static-analysis/delosdb-v1-final-module-target.txt
```

The manifest freezes:

```text
the exact 21 final module names
the three retirement targets
the two required provider artifacts
the build-only RawStore patch artifact
the forbidden production dependency edges
the neutral API modules
```

## Permanent gate

The task:

```text
delosV1ModuleArchitectureStaticAnalysis
```

checks both the current migration state and the final destination.

It proves now that:

```text
delosdb-engine has no Gradle dependency on MVCC or Lucene implementations
delosdb-storage-derby has no dependency/import on MVCC or Lucene implementations
delosdb-storage-mvcc has no dependency/import on engine or Lucene implementations
runtime-api, spi, and derby-store-api expose no Lucene implementation types
RAMAccessManager discovers access methods through ExternalAccessMethodProvider
settings.gradle contains only modules belonging to the frozen migration set
current module count is consistent with Lucene addition and legacy-module retirement state
```

It is wired into `s0CloseoutVerification` alongside the existing objective module-dependency report.
It implements the current `verifyStorageAuthorityModuleGraph` and migration-time
`verifyProviderIsolation` requirements under DelosDB task naming.

Final closeout later adds strict gates equivalent to:

```text
verifyNoIndependentMvccIo
verifyNoIndependentMvccDurability
verifyNoLegacyLucene
verifyRetiredStorageModules
```

## Production provider registration

`delosdb-storage-bridge` is absent. `delosdb-storage-mvcc` owns exactly one
`ExternalAccessMethodProvider` implementation and service entry.

The engine consumes `delosdb-storage-mvcc.jar` only through the build-time
`derbyRuntimePatchElements` configuration. The provider implementation is incorporated into
`derby.jar` for the current compatibility runtime and the provider jar is not simultaneously placed
on the runtime classpath. This preserves neutral ServiceLoader discovery without a production compile
edge or split-package runtime duplication.

## Final dependency direction

```text
delosdb-engine
    -> delosdb-runtime-api
    -> delosdb-derby-store-api
    -> delosdb-spi

DelosDB RawStore implementation
    -> delosdb-runtime-api
    -> delosdb-derby-store-api

DelosDB MVCC provider
    -> delosdb-runtime-api
    -> delosdb-derby-store-api
    -> delosdb-spi

DelosDB Lucene provider
    -> delosdb-runtime-api
    -> delosdb-derby-store-api
    -> delosdb-spi
    -> Lucene
```

Forbidden:

```text
engine -> MVCC implementation
engine -> Lucene implementation
RawStore implementation -> MVCC or Lucene implementation
MVCC -> engine or Lucene implementation
Lucene -> engine implementation
Lucene types -> neutral DelosDB APIs
```

## Final artifacts

Required provider artifacts:

```text
delosdb-storage-mvcc.jar
delosdb-search-lucene.jar
```

Build-intermediate only:

```text
delosdb-storage-derby.jar
```

The final distribution must not advertise:

```text
delosdb-storage-io.jar
delosdb-storage-bridge.jar
delosdb-storage-derby.jar
```

## Scope boundary

The storage-project retirement does not:

```text
merge MVCC or Lucene implementations into the engine
add delosdb-search-lucene
change SQL routing or table formats
change transaction, recovery, locking, vacuum, or maintenance semantics
delete the quarantined pre-convergence differential oracle
place both derby.jar and the MVCC patch jar on the runtime classpath
```
