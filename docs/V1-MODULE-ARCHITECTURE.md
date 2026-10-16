# DelosDB v1 module architecture

## Purpose

This document records the current Gradle module graph, the production dependency boundaries, and the
small amount of deferred structure represented in the checked architecture manifest.

## Current module graph

The current build contains 18 Gradle subprojects:

```text
delosdb-osgi-stub
delosdb-commons
delosdb-runtime-api
delosdb-annotations
delosdb-spi
delosdb-derby-store-api
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

The former projects below are retired and are not part of the current build:

```text
delosdb-storage-api
delosdb-storage-bridge
delosdb-storage-io
delosdb-storage-mvcc
delosdb-storage-derby
```

`delosdb-storage-mvcc` and `delosdb-storage-derby` were merged into `delosdb-engine`, keeping their
packages (`impl.store.access.mvcc`, and the rest of `impl.store`), as part of the engine build
consolidation in `docs/design/REFERENCE-CODEBASE-STRUCTURE.md`.

## Ownership rules

```text
RawStore is the sole physical persistence and recovery authority.
The inherited heap and delos_mvcc are peer access methods over that authority.
SQL code depends on neutral contracts rather than the MVCC implementation package.
The MVCC implementation depends only on the store contracts, not on SQL, RawStore or heap code.
Retired storage modules do not return through build-output or classpath shortcuts.
```

## Dependency direction

The important current production direction is:

```text
delosdb-runtime-api       delosdb-spi
          \                 /
           delosdb-derby-store-api
                 |
           delosdb-engine
           (SQL; RawStore and heap in impl.store;
            MVCC in impl.store.access.mvcc, found through neutral discovery)
```

Representative allowed dependencies are:

```text
delosdb-engine
    -> delosdb-runtime-api
    -> delosdb-derby-store-api
    -> delosdb-spi
    -> delosdb-commons
    -> delosdb-annotations
```

Inside the engine module, package groups replace project boundaries. The checked manifest
`gradle/static-analysis/delosdb-engine-package-boundaries.txt` lets SQL code use the store contracts
and kernel services but not the RawStore, heap or MVCC implementation, lets MVCC use only the store
contracts, and keeps RawStore and heap code off MVCC. `delosModuleDependencyBoundaryStaticAnalysis`
enforces it.

## Provider discovery and packaging

The engine's `impl.store.access.mvcc` package owns the production `ExternalAccessMethodProvider`
implementation:

```text
org.apache.derby.impl.store.access.mvcc.DerbyMvccAccessMethodProvider
```

It ships in `derby.jar` with its provider registration; the separate `delosdb-storage-mvcc.jar` is
retired. Discovery through `ExternalAccessMethodProvider` remains for optional providers such as the
deferred `delosdb-search-lucene`.

`delosdb-derby-store-api.jar` is likewise an assembled support artifact rather than a normal runtime
jar.

The authoritative artifact model lives in:

```text
gradle/delosdb-runtime-artifacts.gradle
```

## Neutral contracts

### `delosdb-runtime-api`

Owns generic runtime and storage contracts that are not specific to heap or MVCC implementation.

### `delosdb-spi`

Owns provider-neutral extension contracts. Implementation-specific MVCC classes do not appear in
these APIs.

### `delosdb-derby-store-api`

Owns the narrow shared contracts between Derby access/transaction code, RawStore implementation, and
external access-method providers.

### RawStore and heap (`delosdb-engine`, `impl.store` except `access.mvcc`)

Own DelosDB's RawStore implementation, the inherited heap and B-tree access methods, and their
integration. The package-boundary check keeps them below access-method providers: they use only the
store contracts.

### MVCC access method (`delosdb-engine`, `impl.store.access.mvcc`)

Owns the RawStore-backed `delos_mvcc` access method, its provider registration, logical MVCC state,
indexes, maintenance, and diagnostics.

## Deferred optional search module

The checked target manifest also reserves the name `delosdb-search-lucene` as a **deferred** optional
provider. It is not present in `settings.gradle`, not built, and not part of the current runtime.

Its presence in `gradle/static-analysis/delosdb-v1-final-module-target.txt` means only that the
architecture verifier knows the permitted future boundary. It must not be documented or packaged as
a current DelosDB capability.

## Structural authority

The current architecture is checked by:

```text
delosModuleDependencyBoundaryStaticAnalysis
delosV1ModuleArchitectureStaticAnalysis
verifyDelosRuntimeStorageProviders
```

`delosV1ModuleArchitectureStaticAnalysis` reads:

```text
gradle/static-analysis/delosdb-v1-final-module-target.txt
```

The manifest distinguishes active modules from deferred modules and verifies that the current
`settings.gradle` contains no unexpected module and omits no non-deferred target module.

## Verification

```bash
./gradlew delosModuleDependencyBoundaryStaticAnalysis \
          delosV1ModuleArchitectureStaticAnalysis \
          verifyDelosRuntimeStorageProviders \
          --console=plain
```

The product-wide verification task also includes these permanent structural checks:

```bash
./gradlew s0CloseoutVerification --console=plain
```
