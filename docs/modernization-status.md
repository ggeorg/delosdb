# DelosDB Modernization Status

Last updated: 2026-06-14

DelosDB is a Gradle-only Java 21 modernization fork of Apache Derby with a
Derby-compatible SQL/JDBC baseline and a controlled extension platform.

The current priority is cleanup and completion, not expansion. No new provider
family should be added until `CostModelProvider` reaches a v2 proof with two real
implementations. Workspace metadata such as `.git/`, `.gradle/`, and `.idea/` is
valid local state and must not be deleted by cleanup scripts.

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
- `IndexProvider` v0/v1 surface.
- `StorageProvider` v0 surface.
- `FunctionProvider` v0 surface.
- `CostModelProvider` v1 native store-cost adapter.
- `TypeProvider` v0 metadata and SQL visibility.
- unified extension registry through `SYSCS_UTIL.DELOSDB_EXTENSIONS()`.
- type metadata visibility through `SYSCS_UTIL.DELOSDB_TYPES()`.
- system-routine permission test baseline centralized in `DelosDbTestBaselines`.

## Current modernization status

Completed modernization work includes:

- Java 21 Gradle-only build path.
- runtime jar verification and Maven Local publication checks.
- binary distribution foundation.
- inherited Derby message/resource generation through Gradle.
- inherited Derby lang/JDBC suite on the Gradle classpath.
- production modernization audit script.
- benchmark baseline script.
- selected Java 21 cleanup batches for finalizers, diagnostics, collection usage,
  timers, logging, and test activation.

## Current cleanup priority

Before adding features:

1. remove stale checkpoint documents with `scripts/remove-checkpoint-docs.sh`,
2. create shareable ZIP snapshots with `scripts/create-clean-snapshot.sh` instead of deleting local `.git/`, `.gradle/`, or `.idea/`,
3. keep generated LaTeX/PDF build outputs out of source control,
4. verify book source citations chapter by chapter before treating the manuscript
   as reliable,
5. reduce inherited `RESOLVE` comments in focused batches,
6. reduce `instanceof`-then-cast patterns only where ownership and behavior are
   clear,
7. finish `CostModelProvider` v2 with heap and B-tree provider implementations.
