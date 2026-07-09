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

## JMH storage benchmark adapter

`delosJmhStorageBenchmarkAdapter` is the opt-in storage/index/page JMH lane.
It first runs the built-in deterministic MVCC microbenchmark validation and then
validates the dedicated benchmark sources under:

```text
benchmarks/jmh/delosdb-storage-mvcc
```

The benchmark source is intentionally outside normal Gradle source sets. Normal
S0, module checks, and SQL integration tests do not compile JMH sources and do
not require JMH dependencies.

Run the adapter report and deterministic baseline:

```bash
./gradlew delosJmhStorageBenchmarkAdapter
```

Run an approved external JMH command:

```bash
./gradlew delosJmhStorageBenchmarkAdapter \
  -Pdelosdb.jmh.storage.command="<compile-and-run JMH command>"
```

The current benchmark classes are:

* `DelosMvccPageCodecBenchmark`
* `DelosMvccOrderedIndexBenchmark`
* `DelosMvccBufferCacheBenchmark`

Benchmark results are advisory. They must not drive storage-format, optimizer,
recovery, or heap/raw-store changes without a separate proof slice and normal
DelosDB gates.
