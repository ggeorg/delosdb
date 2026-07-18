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

Object deserialization boundary gate:

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


## Security truth gates

Run the focused runtime and static security gates with:

```bash
./gradlew \
  :delosdb-tests:runDelosSecurityTruthTest \
  delosSecurityTruthStaticAnalysis \
  delosHeapObjectDeserializationFilterStaticAnalysis
```

These gates protect TLS keystore null/stream handling, truthful TLS mode documentation, secure
PlanExporter XML processing, fail-closed external deserialization, the separate bounded heap
policy, and group-commit fatal-error release semantics.

## Production-closeout v1 evidence capture

After normal correctness gates are green, run the complete machine-specific capture from a clean
JDK 25 checkout:

```bash
./gradlew :delosdb-tests:captureDelosV1Baseline --console=plain
```

The opt-in task builds and runs the jlink/JPMS DRDA lane, captures split raw decision-force and
participant-publication timing, and writes the self-contained bundle under
`build/reports/delosdb/v1-baseline/capture/` with status `CAPTURED_NOT_ACCEPTED`.

The existing `promoteDelosV1Baseline` task is pinned to the historical accepted checksum and must
not be used for the new `phase8-v1-production-closeout` capture. Review its manifest and semantic
checksum first; promotion requires a new acceptance candidate and immutable destination. See
[`V1-BASELINE.md`](V1-BASELINE.md).
