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
./gradlew :delosdb-tests:delosFunctionalTests :delosdb-tests:delosConcurrencyTests :delosdb-tests:delosRecoveryTests --console=plain
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

## Test inventory, provenance, authorship and stable suites

Stages 1 through 3 of the accepted test-organization plan freeze the inherited Derby baseline,
separate DelosDB-authored tests physically and provide stable purpose-oriented execution tasks.
Verify provenance and stable-suite coverage with:

```bash
./gradlew \
  :delosdb-tests:delosTestProvenanceStaticAnalysis \
  :delosdb-tests:delosStableTestSuiteStaticAnalysis \
  --console=plain
```

Reports are written to:

```text
delosdb-tests/build/reports/tests/delosdb-test-inventory.json
delosdb-tests/build/reports/tests/delosdb-test-inventory.txt
delosdb-tests/build/reports/tests/inherited-derby-test-provenance.json
delosdb-tests/build/reports/tests/inherited-derby-test-provenance.txt
delosdb-tests/build/reports/tests/delosdb-stable-test-suites.json
delosdb-tests/build/reports/tests/delosdb-stable-test-suites.txt
delosdb-tests/build/reports/tests/delosdb-isolation-specifications.json
delosdb-tests/build/reports/tests/delosdb-isolation-specifications.txt
```

The Apache Derby 10.17.1.0 source baseline, explicit inherited adaptation manifest and the narrow
adaptation-support manifest are tracked under `gradle/testing/`. Line-ending-only variants are
reported separately and are not treated as behavioral adaptations. The active source roots are:

```text
delosdb-tests/src/test/java
delosdb-tests/src/delosTest/java
delosdb-tests/src/delosTestSupport/java
delosdb-tests/src/delosTest/resources
```

## Stable test entry points

Inherited Derby authority:

```text
derbyUnitTests
derbyLanguageTests
derbyNistSql92Tests
derbyJdbcTests
derbyStoreTests
derbyNetworkTests
derbyToolsTests
derbyUpgradeTests
derbyAllTests
```

DelosDB authority:

```text
delosUnitTests
delosFunctionalTests
delosConcurrencyTests
delosRecoveryTests
delosSystemTests
delosStressTests
```

The complete Stage 4 isolation catalogue has a focused entry point:

```bash
./gradlew \
  :delosdb-tests:delosIsolationSpecStaticAnalysis \
  :delosdb-tests:runDelosIsolationSpecificationTests \
  --console=plain
```

Its 25 JSON specifications live under `src/delosTest/resources` and are inventoried with frozen
PostgreSQL-methodology provenance under `gradle/testing/`. They cover snapshot stability, savepoints,
deadlocks, update/delete traversal, foreign-key concurrency, DDL conflicts, and concurrent `MERGE`
across applicable heap/MVCC and file/memory configurations. The runner verifies actual Derby
heavyweight lock waits through `SYSCS_DIAG.LOCK_TABLE`; incomplete async steps alone are not accepted
as proof of blocking. The complete catalogue is also part of `delosConcurrencyTests` and root `check`.
See [ISOLATION-SPECIFICATIONS.md](ISOLATION-SPECIFICATIONS.md) for the format and authoring rules.

Verification levels:

```bash
./gradlew test --console=plain
./gradlew quickVerification --console=plain
./gradlew check --console=plain
./gradlew fullVerification --console=plain
./gradlew nightlyVerification --console=plain
./gradlew releaseVerification --console=plain
```

`test` is unit-only. `quickVerification` adds DelosDB quick smoke and runtime smoke checks. `check`
adds the remaining DelosDB functional tests, concurrency and recovery lanes, Derby language, NIST
SQL-92 and permanent architecture/repository gates. Quick and full-tier functional partitions are
disjoint, so `check` executes each functional test once. `fullVerification` adds the remaining
non-stress DelosDB system tests and principal inherited JDBC/store/network/tools suites.
`nightlyVerification` adds stress. `releaseVerification` runs the release authority directly through
Derby `suites.All`, every DelosDB correctness lane, permanent gates and the release-only
performance/report harnesses; it does not rerun the inherited suites
individually before `suites.All`.

## Storage verification

```bash
./gradlew   :delosdb-derby-store-api:check   :delosdb-storage-derby:check   :delosdb-storage-mvcc:check   :delosdb-tests:delosFunctionalTests :delosdb-tests:delosConcurrencyTests :delosdb-tests:delosRecoveryTests   --console=plain
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
