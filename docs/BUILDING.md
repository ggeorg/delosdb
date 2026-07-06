# Building DelosDB

DelosDB is built with the checked-in Gradle Wrapper.

## Requirements

```text
JDK 25
Gradle Wrapper from this repository
```

Use:

```sh
./gradlew --version
```

Do not rely on a system `gradle` command.

## Main verification commands

Runtime provider gate:

```sh
./gradlew verifyDelosRuntimeStorageProviders
```

Focused MVCC SQL gate:

```sh
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
```

Focused server gate:

```sh
./gradlew :delosdb-tests:runDelosServerSchedulerTest :delosdb-server:compileJava delosServerStaticAnalysis
```

Full verification:

```sh
./gradlew clean fullVerification :delosdb-storage-mvcc:check
```

Static closeout gate:

```sh
./gradlew s0CloseoutVerification
```

## Broader Derby compatibility checks

```sh
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

## Distribution checks

```sh
./gradlew dist
./gradlew verifyReleaseDistribution
```

Runtime jars are emitted under `build/libs/`. Binary distributions are emitted under `build/distributions/`.

## Static-analysis gates

Storage/MVCC static gate:

```sh
./gradlew delosStorageStaticAnalysis
```

Runtime artifact model gate:

```sh
./gradlew delosRuntimeArtifactModelStaticAnalysis
```

Heap object deserialization filter gate:

```sh
./gradlew delosHeapObjectDeserializationFilterStaticAnalysis
```

Cross-engine consistency framework gate:

```sh
./gradlew delosCrossEngineConsistencyFrameworkStaticAnalysis
```

Server static gate:

```sh
./gradlew delosServerStaticAnalysis
```

Combined closeout gate:

```sh
./gradlew s0CloseoutVerification
```

## Notes

- The inherited Ant workflow is not supported for DelosDB development.
- Some inherited Derby tests are long-running; use focused DelosDB gates while iterating, then run the full gate before pushing a milestone.
- If a previous Derby test run was interrupted, run a clean verification from the repository root.
