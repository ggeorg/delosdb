# DelosDB v1 module architecture

## Purpose

This document records the current Gradle module graph, the production dependency boundaries, and the
small amount of deferred structure represented in the checked architecture manifest.

## Current module graph

The current build contains 20 Gradle subprojects:

```text
delosdb-osgi-stub
delosdb-commons
delosdb-runtime-api
delosdb-annotations
delosdb-spi
delosdb-derby-store-api
delosdb-storage-mvcc
delosdb-storage-derby
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
```

## Ownership rules

```text
RawStore is the sole physical persistence and recovery authority.
The inherited heap and delos_mvcc are peer access methods over that authority.
The engine depends on neutral contracts rather than the MVCC implementation module.
The MVCC implementation does not depend on engine implementation classes.
Retired storage modules do not return through build-output or classpath shortcuts.
```

## Dependency direction

The important current production direction is:

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
                 delosdb-storage-mvcc
```

Representative allowed dependencies are:

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

delosdb-storage-mvcc
    -> delosdb-runtime-api
    -> delosdb-derby-store-api
    -> delosdb-spi
    -> delosdb-commons
```

Forbidden production directions include:

```text
delosdb-engine       -X-> delosdb-storage-mvcc
delosdb-storage-mvcc -X-> delosdb-engine implementation
delosdb-storage-derby -X-> delosdb-storage-mvcc
```

## Provider discovery and packaging

`delosdb-storage-mvcc` owns the production `ExternalAccessMethodProvider` implementation:

```text
org.apache.derby.impl.store.access.mvcc.DerbyMvccAccessMethodProvider
```

The provider jar is assembled as `delosdb-storage-mvcc.jar`. For the current Derby-compatible
runtime packaging, its implementation package and provider metadata are also incorporated into
`derby.jar`; the separate provider jar is not simultaneously added to the normal runtime classpath.

`delosdb-derby-store-api.jar` is likewise an assembled support artifact rather than a normal runtime
jar. `delosdb-storage-derby.jar` remains build-only.

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

### `delosdb-storage-derby`

Owns DelosDB's RawStore implementation integration and remains below access-method providers in the
dependency graph.

### `delosdb-storage-mvcc`

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

The product-wide closeout task also includes these permanent structural checks:

```bash
./gradlew s0CloseoutVerification --console=plain
```
