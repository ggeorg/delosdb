# Building DelosDB

DelosDB is built with the checked-in Gradle Wrapper. The supported developer path is Java 25 through `./gradlew`.

Do not use a system `gradle` command for this repository; it may run an older Gradle runtime and fail on Java 25 class files. Always use `./gradlew`.

## Requirements

- JDK 25
- The checked-in Gradle Wrapper


## Generated SQL bytecode baseline

The ASM-backed generated SQL bytecode backend emits class files at the project baseline: JDK 25.

This is fixed to the DelosDB baseline, not inferred dynamically from whatever JVM happens to run the process. That keeps generated activation classes reproducible across developer machines.

## Main gates

Use the fast roadmap gate after normal overlays:

```bash
./gradlew clean
./gradlew roadmapVerification
./scripts/module-dependency-tree.py
```

Use the inherited Derby compatibility suite as a slower periodic gate:

```bash
./gradlew fullVerification
```

The inherited Derby lang suite generates its native-authentication jar database
from the current DelosDB runtime before execution, avoids the fixed default
1527 network-server port when possible, and fails fast by default so a fatal
suite setup error does not cascade through thousands of follow-on failures.

To force a specific Derby test base port:

```bash
./gradlew fullVerification -Pdelosdb.derby.basePort=25270
```

To collect all inherited Derby lang failures instead of failing fast:

```bash
./gradlew fullVerification -Pdelosdb.derby.collectAllFailures=true
```

## Storage closeout gates

```bash
bash ./scripts/delete-stale-storage-smoke-dbs.sh

./gradlew :delosdb-storage-io:compileDelosDbStorageIo \
          :delosdb-storage-mvcc:compileDelosDbStorageMvcc \
          :delosdb-storage-derby:compileLegacyDerbyStorage \
          storagePhaseO5FullProviderParityCloseoutSmoke \
          storagePhaseC7StabilizationSmoke
```

## Current storage rule

```text
inherited Derby heap/raw store:
  Derby-owned; do not force through delosdb-storage-io

Delos MVCC page-backed storage:
  may use delosdb-storage-io page-volume contracts

future Delos heap provider:
  may use delosdb-storage-io only after source-gated heap work proves the boundary
```

## Cleanup rule

Do not add one-off Gradle tasks for temporary phase proofs. Prefer existing smokes or module-local tests. Stale generated databases should be removed with:

```bash
bash ./scripts/delete-stale-storage-smoke-dbs.sh
```


### Native-authentication fixture generation

`generateNativeAuthenticationTestDatabase` runs Derby's inherited `nast_init.sql` through `ij`. The script deliberately finishes with `connect 'jdbc:derby:;shutdown=true'`, so `ERROR XJ015: Derby system shutdown` is expected and is not treated as a fixture-generation failure. Other `ij` errors still fail the task immediately and point to `build/reports/native-authentication/nast_init.out` and `.err`.
