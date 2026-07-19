# DelosDB v1 module-boundary enforcement

## Status

Stage 2.3 is implemented. This milestone freezes the final 21-subproject destination and adds a
migration-aware permanent gate. It does not remove a module or move table data.

## Final target

The final graph contains 21 Gradle subprojects:

```text
current subprojects: 23
required new module: delosdb-search-lucene
required retired modules: delosdb-storage-api, delosdb-storage-io, delosdb-storage-bridge
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

## Transitional provider registration

`delosdb-storage-bridge` remains present during the RawStore vertical-slice work. While it remains,
the gate requires exactly one `ExternalAccessMethodProvider` implementation and service entry in that
module.

The provider is still patched into `derby.jar` for the current runtime artifact model. This is a
tracked migration condition, not the final module architecture. The engine has no Gradle dependency
on the bridge or MVCC module and performs discovery through the neutral store API.

After bridge absorption, the same gate changes its expectation automatically:

```text
delosdb-storage-bridge absent
delosdb-storage-mvcc publishes ExternalAccessMethodProvider directly
```

Stage 7 must then remove the patched engine provider declaration and make MVCC a separate named
provider module.

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
delosdb-storage-api.jar
delosdb-storage-io.jar
delosdb-storage-bridge.jar
delosdb-storage-derby.jar
```

## Scope boundary

This milestone does not:

```text
remove storage-api, storage-io, or storage-bridge
add delosdb-search-lucene
move bridge classes into storage-mvcc
change SQL routing
create RawStore MVCC containers
change transaction behavior
remove the Phase 8 persistence implementation
```

The next milestone is the complete RawStore-backed MVCC vertical slice.
