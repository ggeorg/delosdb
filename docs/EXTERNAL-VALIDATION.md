# DelosDB external validation tooling

DelosDB includes a CI-friendly external validation layer for MVCC lifecycle work without
making external tools part of normal module checks or permanent verification.

The built-in deterministic harnesses remain the baseline. External tools
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

It lists every external validation slot, whether a command is configured, the matching
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

## Verification boundary

External validation is deliberately not wired into:

* `s0CloseoutVerification`
* `:delosdb-storage-mvcc:check`
* `:delosdb-tests:delosFunctionalTests :delosdb-tests:delosConcurrencyTests :delosdb-tests:delosRecoveryTests`

This keeps compatibility and normal verification deterministic while allowing CI
or release validation to opt into slower tools explicitly.

## JMH performance validation

The repository now contains an executable standalone JMH build under
`benchmarks/jmh`. It consumes assembled runtime jars, targets public JDBC only,
and remains absent from root settings, normal checks, and permanent verification:

```bash
./gradlew jars
./gradlew -p benchmarks/jmh clean check
./gradlew -p benchmarks/jmh clean jmh
```

The stable root adapter remains available for deterministic invariants and
caller-owned CI commands:

```bash
./gradlew delosJmhMicrobenchmarks
./gradlew delosJmhMicrobenchmarks \
  -Pdelosdb.jmh.command="<approved external JMH command>"
```

The built-in baseline is the current page-I/O representation decision proof, not a
wall-clock substitute for JMH. The standalone JMH lane uses public JDBC, checks
semantic fingerprints during measurement, writes JSON and human reports, and
records SHA-256 fingerprints of all runtime jars and benchmark inputs.

## Jcstress concurrency adapter

The stable `delosJcstressConcurrencyValidation` adapter first runs the live RawStore-backed MVCC
network-concurrency proof:

```bash
./gradlew :delosdb-tests:delosSystemTests --tests '*MvccDrdaConcurrentNetworkClientTest'
```

A CI or release job may then supply an approved external jcstress command:

```bash
./gradlew delosJcstressConcurrencyValidation \
  -Pdelosdb.jcstress.command="<approved external jcstress command>"
```

The adapter is opt-in and outside permanent verification.

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

The skeleton is external validation only. It is not wired into permanent verification, does not add
SQLancer as a normal dependency, and does not change storage, optimizer, DRDA,
heap, or MVCC behavior. Generated failures must be minimized and promoted into a
normal deterministic DelosDB regression before they become release gates.

## Deterministic heap/MVCC differential follow-up

Randomized SQLancer findings are not promoted directly into release gates. A
minimized case should first be added to the deterministic heap/MVCC differential
SQL harness:

```bash
./gradlew :delosdb-tests:delosFunctionalTests --tests '*HeapMvccDifferentialSqlHarnessTest'
```

The harness compares supported SQL behavior between inherited Derby heap tables
and `delos_mvcc` tables. It is a normal deterministic regression, not an external dependency lane. The matched workload covers
primary-key and unique-key inserts, secondary indexes, nullable and typed values, rollback and committed
UPDATE/DELETE/INSERT, ordered projections, grouped and scalar aggregates, indexed and date predicates,
provider maintenance, and shutdown/reopen persistence. Intentional provider differences stay outside the
equality probes.
