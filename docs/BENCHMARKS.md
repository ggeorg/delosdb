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

The embedded baseline currently measures:

- database connection creation;
- schema and index creation;
- batch inserts;
- primary-key lookups;
- indexed count queries;
- full-table count.

## Rules for using the numbers

Use the numbers only as a local before/after signal:

- compare the same machine;
- compare the same JDK;
- compare the same working-tree state except for the change being tested;
- do not compare one-off results across machines;
- do not present the output as a formal benchmark result.

A later benchmark phase may add JMH or another repeatable harness, but that is
not part of the current cleanup/provider-hardening work.
