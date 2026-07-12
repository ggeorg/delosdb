# DelosDB external validation tooling

R6 adds a CI-friendly external validation layer for MVCC lifecycle work without
making external tools part of S0 or normal module checks.

The built-in Phase P harnesses remain the deterministic baseline. External tools
wrap those entry points when the caller supplies an approved command.

## Plan report

Generate the external validation plan without running any external tool:

```bash
./gradlew delosExternalMvccValidationPlan
```

The report is written to:

```text
build/reports/delosdb/external-mvcc-validation-plan.txt
```

It lists every R6 validation slot, whether a command is configured, the matching
root task, and the built-in baseline task when one exists.

## Aggregate opt-in task

Run all configured external MVCC validation tools. For a local smoke check of
the command runner, use harmless shell commands such as `echo jmh-ok`; real CI
should replace them with approved JMH, jcstress, SQLancer, workload, or soak
commands.

```bash
./gradlew delosExternalMvccValidation \
  -Pdelosdb.jmh.command="echo jmh-ok" \
  -Pdelosdb.jcstress.command="echo jcstress-ok" \
  -Pdelosdb.sqlancer.command="echo sqlancer-ok"
```

Unconfigured tools are skipped and shown in the plan report. CI can require at
least one configured external tool with:

```bash
./gradlew delosExternalMvccValidation \
  -Pdelosdb.external.validation.required=true \
  -Pdelosdb.sqlancer.command="echo sqlancer-ok"
```

## Individual task slots

These root tasks remain stable opt-in entry points:

* `delosJmhMicrobenchmarks` via `-Pdelosdb.jmh.command="<command>"`
* `delosJcstressConcurrencyValidation` via `-Pdelosdb.jcstress.command="<command>"`
* `delosTwoSidedMvccWorkloadBenchmark` via `-Pdelosdb.twoSided.command="<command>"`
* `delosLongReaderVacuumSoak` via `-Pdelosdb.longReaderSoak.command="<command>"`
* `delosSqlancerMvccValidation` via `-Pdelosdb.sqlancer.command="<command>"`

The first four also run their matching no-dependency built-in validation harness
before any external command. SQLancer is external-only because it is a separate
SQL/JDBC generator.

## S0 boundary

External validation is deliberately not wired into:

* `s0CloseoutVerification`
* `:delosdb-storage-mvcc:check`
* `:delosdb-tests:runDelosMvccSqlIntegrationTest`

This keeps compatibility and normal verification deterministic while allowing CI
or release validation to opt into slower tools explicitly.

## JMH performance validation

`delosJmhMicrobenchmarks` runs the built-in deterministic MVCC microbenchmark
validation. An external JMH invocation may be supplied explicitly:

```bash
./gradlew delosJmhMicrobenchmarks
./gradlew delosJmhMicrobenchmarks \
  -Pdelosdb.jmh.command="<compile-and-run JMH command>"
```

The repository currently has no executable JMH source set. The previous
implementation-coupled storage benchmark sources and standalone build were
removed because they depended on unstable package-private MVCC classes and did
not compile as an isolated build. A future JMH suite must use stable
benchmark-facing APIs or SQL/JDBC workloads and remain outside normal runtime
and S0 dependency paths.

## Jcstress MVCC visibility probes

`delosJcstressMvccVisibilityProbes` validates the opt-in jcstress probe layout
for MVCC visibility, transaction outcome publication, retained snapshot horizon,
and buffer pin/dirty publication. It runs the existing deterministic baseline
first:

```bash
./gradlew :delosdb-storage-mvcc:runDelosMvccConcurrencyValidation
```

Then it validates the external probe sources under:

```text
benchmarks/jcstress/delosdb-storage-mvcc
```

Run the adapter without requiring jcstress:

```bash
./gradlew delosJcstressMvccVisibilityProbes
```

Run a CI-provided jcstress command explicitly:

```bash
./gradlew delosJcstressMvccVisibilityProbes \
  -Pdelosdb.jcstress.visibility.command="<compile-and-run jcstress command>"
```

This task is external validation only. It is not wired into S0 and does not
change MVCC visibility, purge horizon, buffer, checkpoint, Derby heap, DRDA, or
optimizer behavior.

## SQLancer profile skeleton

`delosSqlancerProfileSkeleton` validates the opt-in SQLancer profile contract
under:

```text
benchmarks/sqlancer/delosdb-mvcc
```

Run the skeleton validation without requiring SQLancer:

```bash
./gradlew delosSqlancerProfileSkeleton
```

Run a CI-provided SQLancer command explicitly:

```bash
./gradlew delosSqlancerProfileSkeleton \
  -Pdelosdb.sqlancer.profile.command="<compile-and-run SQLancer command>"
```

The skeleton is external validation only. It is not wired into S0, does not add
SQLancer as a normal dependency, and does not change storage, optimizer, DRDA,
heap, or MVCC behavior. Generated failures must be minimized and promoted into a
normal deterministic DelosDB proof before they become release gates.

## Deterministic heap/MVCC differential follow-up

Randomized SQLancer findings are not promoted directly into release gates. A
minimized case should first be added to the deterministic heap/MVCC differential
SQL harness:

```bash
./gradlew :delosdb-tests:runDelosHeapMvccDifferentialSqlHarnessTest
```

The harness compares supported SQL behavior between inherited Derby heap tables
and opt-in `delos_mvcc` tables. It is a normal deterministic proof, not an
external dependency lane.
