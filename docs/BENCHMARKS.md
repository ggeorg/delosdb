# DelosDB Benchmark Baseline

DelosDB keeps a small local benchmark baseline so modernization work can be
checked against obvious regressions. This is a developer regression signal, not a
formal benchmark suite and not a marketing claim.

## Run

Build first:

```bash
./gradlew clean build
```

Run the embedded baseline:

```bash
./dev/benchmark-baseline.sh
```

Optional arguments:

```bash
./dev/benchmark-baseline.sh <rows> <lookups>
```

Example:

```bash
./dev/benchmark-baseline.sh 10000 2500
```

The report is written to:

```text
build/reports/benchmarks/embedded-baseline.md
```

## What it measures

The embedded baseline currently measures Derby-compatible embedded behavior:

- database connection creation;
- schema and index creation;
- batch inserts;
- primary-key lookups;
- indexed count queries;
- full-table count.

## What it does not measure yet

The current benchmark baseline is not an MVCC research harness. It does not yet
measure:

- `delos_mvcc` statement visibility;
- long-snapshot pressure;
- vacuum/history-prune behavior;
- durable transaction outcome recovery;
- version-aware index behavior;
- row-lock or semi-consistent read behavior.

Those should wait until the corresponding correctness gates exist.

## Rules for using the numbers

Use the numbers only as a local before/after signal:

- compare the same machine;
- compare the same JDK;
- compare the same working-tree state except for the change being tested;
- do not compare one-off results across machines;
- do not present the output as a formal benchmark result.

A later benchmark phase may add JMH or another repeatable harness, but that is
future work. After A52 it may be selected as its own lane; it should not be mixed
with a default-store flip or research-platform buildout.

## MVCC post-A52 note

The A44--A52 MVCC correctness sprint is green, but DelosDB still does not make
performance claims for `delos_mvcc`. A small MVCC benchmark/regression lane may
be selected after A52, but it should be chosen explicitly and should not be mixed
with a default-store flip or research-platform work.
