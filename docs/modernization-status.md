# DelosDB Modernization Status

Last updated: 2026-06-13

DelosDB is a Gradle-only Java 21 modernization fork of Apache Derby with a
Derby-compatible SQL/JDBC baseline and an emerging extension platform.

## Current verification gates

Use these local gates for current product work:

```bash
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

For broader release checks:

```bash
./gradlew fullVerification
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

## Current product seams

Green locally:

- Derby runtime/product smokes through `derbyRuntimeSmoke`.
- inherited Derby lang/JDBC suite through Gradle.
- `IndexProvider` v0.
- `StorageProvider` v0.
- `FunctionProvider` v0.
- unified extension registry.
- SQL extension visibility through `SYSCS_UTIL.DELOSDB_EXTENSIONS()`.
- provider cost diagnostics/fallback safety.

## Current modernization status

Completed modernization work includes:

- Java 21 Gradle-only build path.
- runtime jar verification and Maven Local publication checks.
- binary distribution foundation.
- inherited Derby message/resource generation through Gradle.
- inherited Derby lang/JDBC suite on the Gradle classpath.
- production modernization audit script.
- benchmark baseline script.

## Current near-term priority

Before adding new provider families, keep the existing extension platform clean:

1. CI runs `derbyRuntimeSmoke`.
2. provider registry/resolver infrastructure is shared.
3. function provider metadata semantics are clean.
4. smoke tests use common helpers.
5. planner cost diagnostics are scoped per thread.
6. provider metadata catalog integrity is proven.
