# Building and verifying DelosDB

## Requirements

- JDK 25
- the checked-in Gradle Wrapper
- Python 3 for the module-dependency report

Verify the environment:

```bash
./gradlew --version
```

The inherited Ant build is not a supported DelosDB workflow.

## Build

Compile and run normal module checks:

```bash
./gradlew build --console=plain
```

Build runtime jars:

```bash
./gradlew jars --console=plain
```

Runtime jars are written under `build/libs/`.

## Focused iteration

Run the affected module and the smallest directly relevant proof. Examples:

```bash
./gradlew :delosdb-storage-mvcc:check --console=plain
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest --console=plain
./gradlew :delosdb-tests:runDelosDrdaConcurrentClientStressTest --console=plain
```

Do not run the full Derby language suite after every small change.

## Permanent S0

```bash
./gradlew s0CloseoutVerification --console=plain
```

S0 has seven direct authorities:

```text
delosModuleDependencyBoundaryStaticAnalysis
delosV1ModuleArchitectureStaticAnalysis
delosGeneratedClassStaticAnalysis
delosRepositoryIntegrityStaticAnalysis
verifyDelosRuntimeStorageProviders
delosJdk25ClassFileBytecodeVerifier
:delosdb-tests:runDelosSecurityTruthTest
```

The task graph will also run compilation and packaging prerequisites.

## Generated-class verification

```bash
./gradlew   delosGeneratedClassStaticAnalysis   :delosdb-tests:runDelosGeneratedClassProductionAcceptance   delosJdk25ClassFileBytecodeVerifier   --console=plain
```

The focused acceptance does not run the full Derby language suite.

## Test inventory and provenance

Stage 1 of the accepted test-organization plan is a structural inventory. It does not execute,
move, rename, or consolidate tests. Generate the reports and verify that every inherited Derby
adaptation is documented with:

```bash
./gradlew :delosdb-tests:delosTestProvenanceStaticAnalysis --console=plain
```

Reports are written to:

```text
delosdb-tests/build/reports/tests/delosdb-test-inventory.json
delosdb-tests/build/reports/tests/delosdb-test-inventory.txt
delosdb-tests/build/reports/tests/inherited-derby-test-provenance.json
delosdb-tests/build/reports/tests/inherited-derby-test-provenance.txt
```

The Apache Derby 10.17.1.0 source baseline and explicit DelosDB adaptation manifest are tracked
under `gradle/testing/`. Line-ending-only variants are reported separately and are not treated as
behavioral adaptations.

## Derby compatibility

The inherited language suite is expensive and should be reserved for stage, release, or broad SQL
closeout:

```bash
./gradlew :delosdb-tests:runDerbyLangSuite --console=plain
```

A broader inherited verification lane remains available through:

```bash
./gradlew fullVerification --console=plain
```

## Storage verification

```bash
./gradlew   :delosdb-derby-store-api:check   :delosdb-storage-derby:check   :delosdb-storage-mvcc:check   :delosdb-tests:runDelosMvccSqlIntegrationTest   --console=plain
```

## Baseline evidence capture

Machine-specific performance and resource evidence is opt-in and is not part of S0:

```bash
./gradlew :delosdb-tests:captureDelosV1Baseline --console=plain
```

The output is written under `build/reports/delosdb/v1-baseline/capture/` with status
`CAPTURED_NOT_ACCEPTED`. Promotion requires a clean reviewed capture and an explicit acceptance
candidate. See [`V1-BASELINE.md`](V1-BASELINE.md).

## Reports

Common report locations:

```text
build/reports/delosdb/
build/reports/delosdb-module-dependencies/
delosdb-tests/build/reports/tests/
delosdb-tests/build/test-results/
```

Repository-integrity inventory is written under:

```text
build/reports/delosdb/repository-integrity/inventory/
```

## Gradle locks

A failure such as `Timeout waiting to lock groovy-dsl` means another Gradle process owns the cache
lock; it is not a source or gate failure. Inspect the owner process and stop stale daemons:

```bash
./gradlew --stop
```

## Gate policy

Static gates must use executable or structural evidence. They must not depend on comments, Markdown,
roadmap wording, exact report prose, or a task finding its own name in a Gradle script. See
[`STATIC-GATE-POLICY.md`](STATIC-GATE-POLICY.md).
