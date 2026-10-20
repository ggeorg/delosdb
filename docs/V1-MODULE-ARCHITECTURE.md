# DelosDB v1 module architecture

## Purpose

This document records the current Gradle module graph, the production dependency boundaries, and the
small amount of deferred structure represented in the checked architecture manifest.

## Current module graph

The current build contains 14 Gradle subprojects:

```text
delosdb-osgi-stub
delosdb-commons
delosdb-spi
delosdb-engine
delosdb-client
delosdb-tools
delosdb-runner
delosdb-optionaltools
delosdb-server
delosdb-tests
delosdb-pptesting
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
delosdb-derby-store-api
delosdb-runtime-api
delosdb-storeless
delosdb-annotations
```

`delosdb-storage-mvcc`, `delosdb-storage-derby`, `delosdb-derby-store-api` and `delosdb-runtime-api`
were merged into `delosdb-engine`, keeping their packages (`impl.store.access.mvcc`, the rest of
`impl.store`, `iapi.store`, and the kernel contracts in `org.apache.derby.io`, `iapi.util`,
`iapi.xml` and `iapi.services.*`), by the engine build consolidation in
`docs/design/REFERENCE-CODEBASE-STRUCTURE.md`. The engine module exports the kernel contracts as
Derby's does; the module `io.github.ggeorg.delosdb.runtime.api` no longer exists.

`delosdb-storeless` was Derby's `java/storeless` prototype (`StorelessService`,
`StorelessDatabase`, `NoOpTransaction`, `EmptyDictionary`), which boots compiler and optimizer
services without a physical store; DelosDB carried it with small adaptations. It was retired because
nothing exercised it: the build only compiled it, and the smoke checks its README named no longer
existed. A review reported that it does not boot normally, and that with statement caching disabled
a simple query runs but statement cleanup then breaks the connection; upstream Derby compiles the same
code. `EngineType.STORELESS_ENGINE` stays, as part of Derby's inherited module-boot protocol.
Restoring the prototype means taking it from Derby 10.17.1.0 or from git history, and adding a test
that boots it.

`delosdb-annotations` held the stability annotations (`@InternalApi`, `@LegacyInternal`,
`@ExperimentalSpi`). Its only users were `delosdb-spi` and the engine, which already requires the
SPI, and because the engine's published POM did not name either module, the engine could not start
as a Java module from Maven. The annotations moved into `delosdb-spi` under the same package,
`io.github.ggeorg.delosdb.spi.annotation`; the unused `@PublicSpi` was removed.

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
delosdb-commons          delosdb-spi
        \                  /
          delosdb-engine
           (kernel contracts and services; SQL; store contracts in iapi.store;
            RawStore and heap in impl.store; MVCC in impl.store.access.mvcc,
            found through neutral discovery)
```

Representative allowed dependencies are:

```text
delosdb-engine
    -> delosdb-spi
    -> delosdb-commons
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
deferred `delosdb-search-lucene`. The store contracts are in `derby.jar` too; no separate store
jar is assembled.

Message translations ship as Derby's `derbyLocale_<locale>.jar` files, one per locale, built by
`delosdb-locales` from the engine and tools translations and the module templates in
`delosdb-locales/src/main/templates`. Each holds the engine messages split into
`org/apache/derby/loc/locale_<locale>/m<N>_<locale>.properties`, the tool messages under
`org/apache/derby/loc/tools/locale_<locale>`, and the module `org.apache.derby.locale_<locale>`.
`derby.jar`'s manifest `Class-Path` names them, as Derby's does. Client and DRDA translations stay
in `derbyclient.jar` and `derbynet.jar`. Derby's test-only `qq_PP_testOnly` locale is generated
onto the test class path only. `derbyI18nTests` runs Derby's internationalization suite.

The authoritative artifact model lives in:

```text
gradle/delosdb-runtime-artifacts.gradle
```

## Neutral contracts

### Kernel contracts (`delosdb-engine`)

Generic runtime contracts that are not specific to SQL, heap or MVCC: `org.apache.derby.io`,
`iapi.util`, `iapi.xml` and the `iapi.services.*` contracts (monitor, contexts, locks, cache, and so
on). The package-boundary check lets every engine group use them and lets them use nothing else in
the engine.

### `delosdb-spi`

Owns provider-neutral extension contracts and the annotations that mark API stability
(`@InternalApi`, `@LegacyInternal`, `@ExperimentalSpi`). Implementation-specific MVCC classes do not
appear in these APIs. It stays separate from the engine so that an optional provider, such as the
deferred `delosdb-search-lucene`, compiles against it without the engine.

### Store contracts (`delosdb-engine`, `iapi.store`)

The narrow shared contracts between Derby access/transaction code, the RawStore implementation, and
access-method providers. They are a source-ownership boundary, not a public DelosDB storage API: the
package-boundary check lets them use nothing else in the engine.

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
