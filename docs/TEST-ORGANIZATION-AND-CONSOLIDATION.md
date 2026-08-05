# DelosDB Final Test Organization and Consolidation Plan

Status: accepted implementation plan. Stages 1 through 4 are implemented; Stage 5 is next.

## Current implementation checkpoint

The implemented source boundary is:

```text
src/test/java
    Apache Derby 10.17.1.0 inherited corpus
    documented inherited adaptations
    two documented adaptation-support helpers required by inherited adapted tests

src/delosTest/java
    DelosDB-authored executable tests and suites

src/delosTestSupport/java
    DelosDB-owned fixtures, harnesses, benchmark support and assertions

src/delosTest/resources
    DelosDB-owned declarative test specifications and fixtures
```

Stage 2 preserves package names, class names, focused Gradle task names and the single
`org.apache.derby.tests` compilation output. The physical source root is the authorship authority.

Stage 3 adds the stable execution registry at:

```text
gradle/testing/delos-test-suite-registry.tsv
```

The Stage 3 baseline accounted for all 150 DelosDB executable-source files then present in
`src/delosTest/java`. The explicit active authority at
`gradle/testing/delos-stage3-active-test-authority.tsv` records the 77 classes which had an execution
lane before Stage 3—75 through focused Gradle tasks and two DelosDB-authored tests formerly registered
in inherited suites (`HeapSanityCheckerTest` in `store._Suite` and
`NetworkServerControlInaddrAnyTest` in `derbynet._Suite`)—plus reviewed later additions such as the
Stage 4 isolation runner. The 73 dormant Stage 3 sources remain `RETAINED_TRANSITIONAL` with no active
suite or tier until Stage 7 maps their assertions and either restores them against current production
contracts or moves them to historical evidence. Abstract support anchors and shared fixtures remain in
`src/delosTestSupport/java` and are covered by the separate provenance inventory. Every active test
has one purpose suite and one execution tier. The stable inherited and DelosDB task names are now
available at both the root and `:delosdb-tests` project. Existing one-class tasks remain temporary
compatibility lanes until Stage 8.

The inherited `derbynet._Suite` and `store._Suite` no longer register DelosDB-authored tests.
The provenance gate verifies that inherited sources do not depend on either DelosDB source root.
The Stage 3 suite gate verifies complete registry coverage, exact equality between the active registry
and the explicit active authority, exact complementary retention of all dormant sources, valid
active suite/tier assignments, disjoint quick/full functional partitions, stable suite tasks and all
six root verification levels. `quickVerification` explicitly includes the DelosDB unit suite because
the root module-local `test` aggregate intentionally excludes `:delosdb-tests`. The root `check` graph
runs each active functional test once, and `releaseVerification` executes Derby `suites.All` directly
rather than first rerunning the inherited component suites.

Stage 4 adds the DelosDB-owned isolation specification format and complete first catalogue. Twenty-five
stable case IDs cover snapshot stability, savepoint rollback, two- and three-session deadlocks,
update/delete traversal, foreign-key concurrency, DDL conflicts, and concurrent `MERGE`. Specifications
declare setup, teardown, sessions, named steps, provider-specific permutations, asynchronous
start/await operations, observed heavyweight-lock blocking, accepted SQLStates, exact SQLState-count
assertions, and final-state queries. The catalogue executes across heap and `delos_mvcc` and across file
and memory databases wherever the scenario is applicable.

The RawStore MVCC scan boundary now consumes Derby's store isolation level: READ COMMITTED and
weaker scans use a statement-scoped snapshot lease, while REPEATABLE READ keeps the transaction
snapshot. This closes the previously exposed non-refreshing READ COMMITTED behavior.

The runner follows PostgreSQL isolation-test methodology without copying PostgreSQL's parser, runner,
SQL, or expected files. It observes Derby heavyweight lock waits through `SYSCS_DIAG.LOCK_TABLE`, uses
bounded worker operations, drains multi-session deadlocks by committing whichever session completes,
and requires exact provider-specific deadlock/write-conflict outcome bounds. Every case is inventoried and records the PostgreSQL 19beta1
archive fingerprint, source scenario, license, adaptation type, semantic intent, DelosDB changes, and
applicable providers. Embedded execution is the Stage 4 authority; existing DRDA system and failure-path
suites remain the transport-equivalence authority rather than adding network-server lifecycle complexity
to the isolation runner.

The stable registry contains 151 executable-source files: 78 active tests and 73 retained transitional
sources. The shared support root contains 38 DelosDB-owned support classes. Stage 5 begins with H2-style
deterministic differential fuzzing.


## 1. Objective

DelosDB consists of inherited Derby code and new DelosDB code, maintained as one product codebase.

Its tests have two different provenance authorities:

```text
Inherited Derby tests
    -> preserve upstream Derby behavior
    -> remain directly comparable with Derby 10.17.1.0

DelosDB-authored tests
    -> verify DelosDB behavior across inherited and new code
    -> organized by purpose, subsystem and execution tier
```

The consolidation must improve:

```text
test discoverability
upstream comparison
coverage visibility
execution predictability
fixture reuse
Gradle simplicity
failure diagnosis
long-term maintainability
```

It must not:

```text
delete regression evidence prematurely
rewrite the inherited Derby suite
force a wholesale JUnit migration
weaken JPMS boundaries
create another elaborate test framework
copy foreign test repositories wholesale
```

---

# 2. Authoritative test categories

## 2.1 Inherited Derby authority

The inherited Derby test corpus remains structurally separate.

It verifies:

```text
Derby SQL compatibility
JDBC behavior
DRDA behavior
store behavior
upgrade behavior
security behavior
tooling
memory databases
language semantics
SQLStates
```

It remains comparable with:

```text
Apache Derby 10.17.1.0
```

## 2.2 DelosDB authority

DelosDB-authored tests verify:

```text
modified inherited behavior
new DelosDB behavior
RawStore convergence
heap behavior
MVCC behavior
heap/MVCC equivalence
file/memory equivalence
embedded/DRDA equivalence
transaction correctness
recovery
security
module architecture
runtime artifacts
```

A DelosDB-authored test remains a DelosDB test even when it tests only inherited Derby production code.

Provenance is based on who authored the test, not on the origin of the production class under test.

---

# 3. Initial source structure

Use a deliberately simple source layout first:

```text
delosdb-tests/
    src/
        test/
            java/
            resources/

        delosTest/
            java/
            resources/

        delosTestSupport/
            java/
            resources/
```

## `src/test`

Contains the inherited Derby test corpus.

Rules:

```text
upstream packages remain unchanged
upstream file names remain unchanged
JUnit 3 remains unchanged
suite structures remain unchanged
no new DelosDB test classes are added
```

## `src/delosTest`

Contains every DelosDB-authored integration, functional and system test.

Tests may temporarily retain packages such as:

```text
org.apache.derbyTesting.functionTests.tests.store
org.apache.derbyTesting.functionTests.tests.derbynet
```

where package access is necessary.

The physical source root establishes their DelosDB provenance.

## `src/delosTestSupport`

Contains shared DelosDB-owned fixtures and assertions.

Examples:

```text
DatabaseFixture
StorageProviderFixture
NetworkServerFixture
RecoveryFixture
FaultInjectionFixture
DifferentialFixture
SqlAssertions
DiagnosticsAssertions
TemporaryDatabaseFixture
```

Inherited Derby tests must not depend on this source root.

---

# 4. Inherited Derby adaptations

The inherited Derby corpus should remain as close to upstream as possible.

The first implementation will not introduce a complicated duplicate-FQCN adaptation source root.

Instead, maintain an explicit adaptation manifest:

```text
gradle/testing/derby-test-adaptations.tsv
```

Each modified inherited test file records:

```text
relative path
upstream SHA-256
DelosDB SHA-256
reason
issue or architecture phase
expected behavioral difference
temporary or permanent status
```

Typical valid reasons:

```text
JDK 25 adaptation
JPMS adaptation
DelosDB artifact naming
removed obsolete JVM behavior
security hardening
runtime-image behavior
DelosDB-supported platform matrix
```

Permanent gate:

```text
every modified inherited test must appear in the adaptation manifest
```

A physical adaptation overlay may be introduced later only if necessary to restore a byte-identical inherited source tree without creating Gradle or IDE complexity.

---

# 5. Move DelosDB tests out of the inherited corpus

All DelosDB-authored tests currently located inside inherited test directories move to:

```text
src/delosTest
```

This includes DelosDB additions currently under inherited packages such as:

```text
functionTests/tests/store
functionTests/tests/derbynet
junit
unitTests/store
```

Do not modify inherited `_Suite.java` files to register DelosDB tests.

DelosDB suites are composed independently through Gradle and DelosDB-owned suite definitions.

---

# 6. Test metadata model

Do not force one semantic purpose per test.

Every DelosDB test receives:

```text
one owning subsystem
one execution tier
one or more purpose tags
```

## Owning subsystem

Examples:

```text
engine
compiler
optimizer
storage-derby
storage-mvcc
rawstore
jdbc
drda
security
runtime
build
lucene
```

The owner determines maintenance responsibility.

## Purpose tags

Available tags include:

```text
unit
contract
sql
storage
transaction
isolation
concurrency
recovery
network
security
differential
acceptance
stress
performance-harness
```

A test may have several tags.

Example:

```text
HeapMvccIsolationDifferentialTest

Owner:
    storage-mvcc

Tags:
    isolation
    transaction
    differential
    concurrency

Tier:
    full
```

## Execution tiers

```text
unit
quick
full
nightly
release
```

Execution tier controls when a test runs.

Purpose tags control suite filtering and reporting.

---

# 7. JUnit policy

## Inherited Derby tests

Remain on JUnit 3.

Reasons:

```text
upstream comparability
existing Derby fixtures
suite compatibility
minimal source divergence
stable historical behavior
```

## Existing DelosDB tests

Existing DelosDB tests may remain JUnit 3 when they rely on:

```text
BaseJDBCTestCase
BaseTestSuite
TestConfiguration
CleanDatabaseTestSetup
SecurityManagerSetup
```

There will be no mechanical mass conversion.

## New DelosDB tests

New independent tests should generally use JUnit 5 where it provides value:

```text
parameterized tests
tags
dynamic cases
better lifecycle controls
```

JUnit version is not itself a modernization target.

Correctness and maintainability are the targets.

---

# 8. Stable Gradle test entry points

Replace the large number of permanent one-class tasks with a small stable suite model.

## Inherited Derby tasks

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

Mappings include:

```text
derbyLanguageTests
    -> org.apache.derbyTesting.functionTests.tests.lang._Suite

derbyNistSql92Tests
    -> org.apache.derbyTesting.functionTests.tests.nist.NistScripts

derbyAllTests
    -> org.apache.derbyTesting.functionTests.suites.All
```

The existing task:

```text
runDerbyLangSuite
```

may remain temporarily as an alias for:

```text
derbyLanguageTests
```

## DelosDB tasks

```text
delosUnitTests
delosFunctionalTests
delosConcurrencyTests
delosRecoveryTests
delosSystemTests
delosStressTests
```

Suggested grouping:

```text
delosUnitTests
    -> unit and narrow contract tests

delosFunctionalTests
    -> SQL, storage, transaction, security and API contracts

delosConcurrencyTests
    -> isolation, deadlocks, concurrent DDL/DML and concurrency differential tests

delosRecoveryTests
    -> WAL, restart, checkpoint, fault injection, backup and restore

delosSystemTests
    -> DRDA, module images, runtime artifacts, upgrade and end-to-end acceptance

delosStressTests
    -> long-running, large-scale and high-contention tests
```

Developers can still run one class or method with standard Gradle filters:

```bash
./gradlew \
  :delosdb-tests:delosFunctionalTests \
  --tests '*MvccCommitVisibilityTest'
```

Specialized one-class Gradle tasks remain only when a test requires:

```text
a subprocess restart
special JVM arguments
fault-injection configuration
a dedicated network environment
a modular runtime image
an external benchmark harness
```

---

# 9. Root verification levels

## `test`

Runs module-local unit tests only.

## `quickVerification`

Runs:

```text
module-local unit tests
DelosDB contract smoke tests
DelosDB SQL smoke tests
DelosDB storage smoke tests
runtime smoke tests
```

## `check`

Runs:

```text
quickVerification
DelosDB functional tests
selected concurrency tests
selected recovery smoke tests
Derby language suite
NIST SQL-92 suite
architecture and repository gates
```

## `fullVerification`

Runs:

```text
all non-stress DelosDB tests
Derby language tests
Derby JDBC tests
Derby store tests
Derby network tests
Derby memory tests
Derby tools tests
```

## `nightlyVerification`

Runs:

```text
fullVerification
DelosDB stress tests
complete differential matrix
SQLLogicTest
SQLancer
long crash/recovery loops
large concurrency matrices
```

## `releaseVerification`

Runs:

```text
Derby suites.All
all DelosDB tests
all supported storage providers
file and memory databases
embedded and DRDA
backup and restore
security
upgrade
runtime-image validation
artifact verification
permanent architecture gates
accepted baseline verification
```

---

# 10. Provider and environment matrix

DelosDB semantic tests should not be duplicated manually for every storage configuration.

Use reusable fixtures and parameterization.

Primary provider dimension:

```text
heap
delos_mvcc
mixed heap/MVCC
```

Storage dimension:

```text
file
memory
```

Connection dimension:

```text
embedded
DRDA
```

Transaction dimension:

```text
autocommit
explicit transaction
savepoints
supported isolation levels
```

Each test declares applicable configurations.

Example:

```text
Test:
    update then rollback

Providers:
    heap
    MVCC

Storage:
    file
    memory

Connection:
    embedded
    DRDA

Tags:
    SQL
    transaction
    differential
```

The generated report must show results by configuration rather than only one aggregate pass/fail result.

---

# 11. Inherited Derby comparison

Add:

```text
compareInheritedDerbyTests
```

Reports:

```text
build/reports/tests/inherited-derby-comparison.txt
build/reports/tests/inherited-derby-comparison.json
```

Report contents:

```text
upstream Derby version
upstream archive or revision fingerprint
upstream test file count
DelosDB inherited test file count
missing upstream files
unexpected files in inherited root
modified inherited files
documented adaptations
undocumented adaptations
suite-composition differences
resource differences
```

Required result:

```text
Missing upstream tests:             0
Unexpected DelosDB tests:           0
Undocumented inherited changes:     0
```

Where practical, produce a result comparison for portable suites:

```text
upstream Derby result
DelosDB heap result
```

Source equality and behavioral equality are separate reports.

---

# 12. NIST SQL compliance

Keep the inherited NIST SQL-92 suite as an explicit permanent task:

```text
derbyNistSql92Tests
```

Its report should distinguish:

```text
original NIST cases
Derby-adapted cases
disabled cases
passed cases
failed cases
```

Passing it supports a statement such as:

```text
DelosDB passes the inherited Derby-adapted NIST SQL-92 test corpus.
```

It must not be presented as modern SQL:2023 certification.

Modern SQL support will be represented separately through a DelosDB SQL feature matrix.

---

# 13. DelosDB SQL feature matrix

Add:

```text
docs/compatibility/DELOSDB-SQL-FEATURE-MATRIX.md
```

Each supported SQL feature maps to evidence:

```text
parser acceptance
binding
positive semantics
negative behavior and SQLState
heap
MVCC
file database
memory database
embedded
DRDA
transaction behavior
```

Example:

| Feature        | Parser | Semantics | SQLState | Heap |               MVCC | Memory | DRDA |
| -------------- | -----: | --------: | -------: | ---: | -----------------: | -----: | ---: |
| Basic `SELECT` |      ✓ |         ✓ |        ✓ |    ✓ |                  ✓ |      ✓ |    ✓ |
| `MERGE`        |      ✓ |         ✓ |        ✓ |    ✓ |     tested support |      ✓ |    ✓ |
| Savepoints     |    n/a |         ✓ |        ✓ |    ✓ |                  ✓ |      ✓ |    ✓ |
| Triggers       |      ✓ |         ✓ |        ✓ |    ✓ | applicable support |      ✓ |    ✓ |

A feature is not supported merely because the parser recognizes it.

---

# 14. External test strategy

Do not import H2, PostgreSQL or MariaDB test trees wholesale.

Use them as targeted sources of missing scenarios.

Every externally derived test receives provenance metadata:

```text
case ID
source project
source revision
source file
license
adaptation type
original semantic intent
DelosDB changes
applicable providers
```

Adaptation types:

```text
ADAPTED_WITH_ATTRIBUTION
REIMPLEMENTED_FROM_SCENARIO
INDEPENDENT_EQUIVALENT
```

---

# 15. PostgreSQL-derived tests

The first external addition will be a DelosDB-owned isolation specification runner modeled on PostgreSQL’s isolation-test structure.

## DelosDB isolation specification format

Supports:

```text
setup
teardown
multiple sessions
named steps
permutations
expected blocking
expected SQLState
final-state assertions
```

Run applicable specifications against:

```text
heap
MVCC
mixed heap/MVCC
file
memory
embedded
DRDA where meaningful
```

## First scenario tranche

### Snapshot stability

```text
DEL-ISO-001
Snapshot does not gain rows committed after snapshot creation.

DEL-ISO-002
Existing snapshot retains access to the correct old version.

DEL-ISO-003
Commit publication cannot make a previously active transaction visible retroactively.

DEL-ISO-004
Read-only anomaly behavior matches the documented isolation level.
```

### Savepoints and rollback

```text
DEL-ISO-010
Delete then rollback to savepoint.

DEL-ISO-011
Update/delete chain then rollback to savepoint.

DEL-ISO-012
Concurrent reader observes the correct version across savepoint rollback.
```

### Deadlocks

```text
DEL-DEADLOCK-001
Two-row update cycle.

DEL-DEADLOCK-002
Reader/writer conversion deadlock.

DEL-DEADLOCK-003
Three-session deadlock.

DEL-DEADLOCK-004
One victim aborts and surviving transactions remain consistent.
```

### Update/delete traversal

```text
concurrent update and delete
secondary-key update
row-identity preservation
version-chain traversal
READ COMMITTED re-evaluation
```

### Foreign-key concurrency

```text
parent delete versus child insert
foreign-key contention
rollback and retry
deadlock behavior
snapshot behavior
```

### DDL concurrency

```text
table drop versus active reader
index creation versus DML
truncate conflict
trigger lifecycle
```

### `MERGE`

Add concurrent `MERGE` scenarios when DelosDB’s supported semantics are finalized.

The behavior need not match PostgreSQL internals. It must match DelosDB’s documented SQL and isolation contract.

---

# 16. H2-derived test concepts

Reimplement H2’s most valuable testing ideas without copying its internal runner.

## Indexed versus unindexed differential fuzzing

Generate deterministic tables, indexes, predicates and queries.

Compare:

```text
heap indexed versus heap unindexed
MVCC indexed versus MVCC unindexed
heap versus MVCC
file versus memory
prepared versus direct execution
```

Suggested permanent suite:

```text
DelosOptimizerDifferentialFuzzTest
```

## Random expression comparison

Generate bounded expressions using:

```text
integer arithmetic
decimal arithmetic
NULL logic
comparisons
CASE
CAST
string functions
date/time expressions
Boolean predicates
```

Compare all applicable execution paths.

Suggested suite:

```text
DelosExpressionDifferentialTest
```

## Grammar-driven SQL fuzzing

Use a DelosDB-owned supported grammar subset.

Initial invariants:

```text
parser never crashes
binder never corrupts database state
syntax errors use valid SQLStates
subsequent valid statements still execute
```

Suggested suite:

```text
DelosGrammarFuzzTest
```

## Transaction scenarios

Reimplement useful scenarios for:

```text
closing a connection with an active transaction
concurrent updates to the same row
concurrent updates to different rows
commit/rollback races
deadlock detection
statement failure followed by transaction reuse
repeated crash and reopen
```

Every random or generated suite must use deterministic seeds.

A failing seed becomes a permanent minimized regression case.

---

# 17. MariaDB-derived scenario catalogue

Do not copy MariaDB tests or its GPL test runner.

Use MariaDB only to identify scenario gaps.

## Optimizer scenarios

Reimplement applicable cases for:

```text
outer-join reordering boundaries
nested joins
GROUP BY cardinality
NULL-aware cardinality
ORDER BY plus row limit
predicate pushdown
HAVING pushdown
subquery merging
selectivity estimation
window functions
CTEs where supported
```

## Concurrency and recovery scenarios

Reimplement applicable cases for:

```text
snapshot-publication race
lock-upgrade deadlock
deadlock-victim race
lock-wait race
temporary tables and savepoints
rollback after lock timeout
update/delete race
group-commit crash
truncate crash
clean versus abrupt shutdown
truncated or corrupted WAL
```

Do not reproduce MariaDB- or InnoDB-specific internal expectations.

Test DelosDB’s documented behavior.

---

# 18. SQLLogicTest and SQLancer

## SQLLogicTest

Use for broad portable result correctness:

```text
expressions
predicates
joins
aggregates
ordering
NULL behavior
large query combinations
```

It supplements but does not replace transaction, JDBC, recovery or security tests.

Tasks:

```text
sqlLogicTestSmoke
sqlLogicTestFull
```

## SQLancer

Use for generated semantic and optimizer testing.

Profiles:

```text
heap
MVCC
mixed
file
memory
embedded
network where practical
```

Tasks:

```text
sqlancerSmoke
sqlancerNightly
```

Any reproducible SQLancer failure becomes a minimized permanent DelosDB regression test.

---

# 19. Consolidation rules

Do not consolidate tests merely to reduce class count.

A consolidation is valid only when:

```text
all original assertions are inventoried
each regression retains a stable case ID
failure remains independently identifiable
duplicated setup or behavior is actually removed
the replacement is easier to maintain
```

Use parameterized cases where several tests differ only by input or scenario.

Example:

```text
MvccCommitVisibilityTest

Cases:
    MVCC-COMMIT-001
    MVCC-COMMIT-002
    MVCC-COMMIT-003
```

Historical phase or ticket names move to metadata or comments.

They should not remain the permanent behavioral identity.

---

# 20. Milestone-oriented test cleanup

Review tests containing terms such as:

```text
Phase
Stage
Checkpoint
Gate
Quarantine
Removal
Closeout
Skeleton
Foundation
Readiness
Audit
Experiment
Cutover
```

For each test:

```text
retain as a permanent regression
rename by enduring behavior
merge into a parameterized permanent suite
move to historical evidence
or delete after assertion mapping
```

No test is deleted solely because its name is old.

No transitional test family remains active after its final behavior is fully covered.

---

# 21. Report and benchmark separation

JUnit tests should not primarily exist to create:

```text
benchmark reports
JFR evidence
baseline files
architecture inventories
performance summaries
```

Move those responsibilities to:

```text
Gradle report tasks
JMH
JFR campaigns
benchmark scripts
architecture-audit tasks
```

Keep functional assertions in the test suite.

Wall-clock performance thresholds must not become ordinary unit-test assertions unless they validate a deterministic algorithmic bound.

---

# 22. Module-local unit tests

Move a DelosDB unit test beside its owning production module only when:

```text
it has a clean dependency boundary
it does not require central Derby fixtures
it does not require output-directory backdoors
it does not require broader JPMS exports
it does not create a dependency cycle
```

Otherwise, keep it in the central DelosDB test module.

Do not weaken architecture merely to make the source tree look more conventional.

---

# 23. Test inventory and reports

Generate:

```text
build/reports/tests/delosdb-test-inventory.json
build/reports/tests/delosdb-test-inventory.txt
```

Each DelosDB test records:

```text
class
source provenance
owning subsystem
purpose tags
execution tier
provider support
file/memory support
embedded/DRDA support
expected duration
special environment
last result
external provenance where applicable
```

Static gates:

```text
every DelosDB test has an owner
every DelosDB test has a tier
every DelosDB test has at least one purpose tag
no DelosDB test exists in the inherited source root
no inherited test depends on DelosDB test support
no external scenario lacks provenance
no benchmark is executed as a normal unit test
no undocumented inherited adaptation exists
```

---

# 24. Implementation stages

## Stage 1 — inventory and provenance

No behavioral changes.

1. Inventory all current tests.
2. Classify inherited, adapted and DelosDB-authored files.
3. Create the inherited adaptation manifest.
4. Create the test inventory report.
5. Identify DelosDB-authored tests inside inherited packages.
6. Identify report-only and benchmark-style JUnit classes.
7. Identify milestone-oriented test families.

No deletion, rename or semantic modification.

## Stage 2 — authorship separation

1. Add `delosTest` and `delosTestSupport`.
2. Move DelosDB-authored tests out of the inherited source root.
3. Preserve package names initially.
4. Remove DelosDB registrations from inherited suite files.
5. Add independent inherited and DelosDB reports.
6. Prove inherited source comparison.

## Stage 3 — stable Gradle entry points

1. Add the inherited suite tasks.
2. Add the six DelosDB suite tasks.
3. Add `derbyAllTests`.
4. Add explicit NIST execution.
5. Keep existing focused tasks temporarily as deprecated aliases.
6. Define `quickVerification`, `check`, `fullVerification`, `nightlyVerification` and `releaseVerification`.

## Stage 4 — PostgreSQL-style isolation runner

Implemented:

1. DelosDB isolation-spec format and validated loader.
2. Snapshot-stability cases (`DEL-ISO-001` through `DEL-ISO-004`).
3. Savepoint cases (`DEL-ISO-010` through `DEL-ISO-012`).
4. Simple, conversion and three-session deadlocks (`DEL-DEADLOCK-001` through `004`).
5. Update/delete traversal, secondary-key movement, row identity and READ COMMITTED re-evaluation
   (`DEL-TRAVERSAL-001` through `004`).
6. Provider-specific foreign-key contention, explicit rollback/retry, deadlock/rejection and snapshot cases (`DEL-FK-001` through `004`).
7. Provider-specific DROP, CREATE INDEX and TRUNCATE behavior plus trigger-lifecycle conflicts (`DEL-DDL-001` through `004`).
8. Conflicting and disjoint concurrent `MERGE` cases (`DEL-MERGE-001` and `002`).

Permanent Stage 4 gates require the exact 25-case catalogue, all seven categories, complete PostgreSQL
provenance, explicit named permutations, observed lock waits, bounded asynchronous completion, exact
SQLState outcome bounds, provider/storage-scoped final assertions, matrix reporting, and registration
in the concurrency/full lane.

## Stage 5 — H2-style differential fuzzing

1. Add indexed/unindexed differential testing.
2. Add heap/MVCC differential generation.
3. Add random expression testing.
4. Add grammar fuzzing.
5. Add deterministic seed persistence and minimization.

## Stage 6 — external semantic-gap expansion

1. Review PostgreSQL SQL regression cases.
2. Review MariaDB optimizer scenarios.
3. Review MariaDB crash and concurrency scenarios.
4. Adapt only cases covering confirmed DelosDB gaps.
5. Record provenance for every adapted scenario.

## Stage 7 — consolidation

1. Introduce shared DelosDB fixtures.
2. Remove duplicated setup.
3. Parameterize equivalent regression cases.
4. Rename milestone-oriented tests by enduring behavior.
5. Move report generation out of JUnit.
6. Move suitable unit tests to production modules.
7. Delete obsolete transitional tests only after assertion mapping.

## Stage 8 — Gradle cleanup

1. Replace repetitive task registration with a declarative suite registry.
2. Remove deprecated one-class task aliases.
3. Reduce `delosdb-tests/build.gradle`.
4. Enable permanent test-organization gates.
5. Capture the accepted test baseline.

---

# 25. Completion criteria

The consolidation is complete when:

```text
Inherited Derby files missing:
    0

Unexpected DelosDB tests in inherited root:
    0

Undocumented inherited adaptations:
    0

DelosDB tests without owner:
    0

DelosDB tests without tier:
    0

DelosDB tests without purpose tags:
    0

External-derived tests without provenance:
    0

Report-only JUnit tests:
    0

Benchmarks run as ordinary unit tests:
    0

Permanent one-class Gradle tasks:
    only justified special-environment tasks

Inherited suite results:
    independently reportable

DelosDB suite results:
    independently reportable

Combined release result:
    independently reportable
```

The inherited Derby corpus remains independently comparable with Derby 10.17.1.0.

---

# 26. Final target structure

```text
DelosDB verification
    |
    +-- Inherited Derby authority
    |       |
    |       +-- language
    |       +-- NIST SQL-92
    |       +-- JDBC
    |       +-- store
    |       +-- network
    |       +-- tools
    |       +-- upgrade
    |       +-- suites.All
    |
    +-- DelosDB authority
    |       |
    |       +-- unit
    |       +-- functional
    |       +-- concurrency
    |       +-- recovery
    |       +-- system
    |       +-- stress
    |
    +-- Generated and external-derived verification
    |       |
    |       +-- PostgreSQL-style isolation specifications
    |       +-- H2-style differential fuzzing
    |       +-- selected PostgreSQL semantic cases
    |       +-- selected MariaDB scenario reimplementations
    |       +-- SQLLogicTest
    |       +-- SQLancer
    |
    +-- Performance evidence
            |
            +-- JMH
            +-- JFR
            +-- async-profiler
            +-- benchmark reports
```

## Final decision

Freeze these principles:

```text
Inherited Derby tests remain separate and comparable.

DelosDB-authored tests are separate from the inherited corpus.

Tests are organized by owner, purpose tags and execution tier.

No wholesale JUnit rewrite.

No wholesale import of foreign test trees.

PostgreSQL isolation methodology is the first external addition.

H2 differential and generated testing is the second.

MariaDB is a scenario catalogue, not a source dependency.

No test is deleted before its assertions are mapped.

Benchmarks and report generation are separated from correctness testing.
```

The first overlay must implement **Stage 1 only: inventory and provenance**.

It must not move, rename, consolidate or delete tests.

## Stage 3 execution-isolation correction

Stable DelosDB suite tasks preserve the execution boundary of the pre-Stage-3
focused tasks by running one test class per worker JVM. This is required because
Derby's monitor, test configuration, and selected system properties are
process-global. Combining unrelated DelosDB and inherited-harness classes in one
worker can otherwise change database path resolution and module boot state.

DelosDB SQL test support resolves relative file-database paths against
`derby.system.home` when that property is active. Diagnostics, retained-format
markers, and direct filesystem assertions therefore target the same canonical
database directory used by Derby.

