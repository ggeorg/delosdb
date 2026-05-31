# DelosDB benchmark baseline

DelosDB keeps a small local benchmark baseline so modernization work can be checked against obvious performance regressions.

The current benchmark is intentionally simple. It is not a formal microbenchmark suite and should not be used for marketing claims. Its purpose is to give contributors a quick local signal while Java 21 cleanup, lifecycle cleanup, and collection modernization are in progress.

## Embedded baseline

Build the runtime jars first:

```bash
./gradlew clean build
```

Run the embedded benchmark baseline:

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

The script writes a report to:

```text
build/reports/benchmarks/embedded-baseline.md
```

## What it measures

The embedded baseline currently measures:

```text
create database connection
create schema and index
batch insert rows
primary-key lookups
indexed count queries
full-table count
```

## Rules for using the numbers

Use the benchmark as a local regression signal only:

```text
Run it before and after risky cleanup.
Compare the same machine, same JDK, same working tree state.
Do not compare one-off results across different machines.
Do not treat the output as a formal benchmark claim.
```

A later benchmark phase should add JMH or a dedicated repeatable harness for formal measurements.
