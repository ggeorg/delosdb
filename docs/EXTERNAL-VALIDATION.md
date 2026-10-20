# DelosDB external validation tooling

External tools such as JMH, jcstress, SQLancer and long-running soaks stay outside the normal build
and every verification level. The deterministic DelosDB tests below are the baseline; an external
tool adds evidence on top of them and never replaces them.

The root build used to have Gradle tasks that ran a caller-supplied external command
(`delosJmhMicrobenchmarks`, `delosJcstressConcurrencyValidation`, `delosSqlancerMvccValidation`
and others). They were removed in 0cf95e92; a CI job runs an external tool with its own command.

## Deterministic baselines

| Concern | Command |
|---|---|
| Live MVCC network concurrency | `./gradlew :delosdb-tests:delosSystemTests --tests '*MvccDrdaConcurrentNetworkClientTest'` |
| Heap/MVCC differential SQL | `./gradlew :delosdb-tests:delosFunctionalTests --tests '*HeapMvccDifferentialSqlHarnessTest'` |
| Long reader against purge | `./gradlew :delosdb-tests:delosStressTests --tests '*MvccSqlLongReaderPurgeStressTest'` |
| Page-I/O representation | `./gradlew :delosdb-tests:runDelosSharedRawStorePageIoRepresentationDecisionTest` |

The first two run in `fullVerification`, the long-reader test in `nightlyVerification`, and the
page-I/O test, which records diagnostic timing without thresholds, in `releaseVerification` (see
[BUILDING.md](BUILDING.md)).

## JMH and concurrent-commit benchmarks

The standalone build under `benchmarks/jmh` consumes the assembled runtime jars, uses public JDBC
only, and is absent from root settings and every verification level:

```bash
./gradlew jars
./gradlew -p benchmarks/jmh clean check
./gradlew -p benchmarks/jmh clean jmh
./gradlew -p benchmarks/jmh runConcurrentCommitBenchmark
```

The JMH lane checks semantic fingerprints during measurement, writes JSON and human reports, and
records SHA-256 fingerprints of the runtime jars and benchmark inputs. The concurrent-commit
benchmark's commits are dominated by log syncs, whose cost varies widely between runs on a laptop
disk; to compare two builds' code paths, alternate the builds and pass
`-Dderby.system.durability=test` through `JAVA_TOOL_OPTIONS`.

## Jcstress

The repository contains no jcstress harness. Concurrency correctness is covered by the network
concurrency test above and by `delosConcurrencyTests`.

## SQLancer profile

`benchmarks/sqlancer/delosdb-mvcc` describes the scope a SQLancer run against `delos_mvcc` should
use: the supported generator scope, a smoke workload and the oracle scope. Nothing in the build reads
or validates these files, and SQLancer is not a dependency; running it is up to the caller.

Randomized findings are not promoted directly into verification. A minimized case is first added to
the deterministic heap/MVCC differential SQL harness, which compares supported SQL behavior between
inherited Derby heap tables and `delos_mvcc` tables. Its matched workload covers primary-key and
unique-key inserts, secondary indexes, nullable and typed values, rollback and committed
UPDATE/DELETE/INSERT, ordered projections, grouped and scalar aggregates, indexed and date
predicates, provider maintenance, and shutdown/reopen persistence. Intentional provider differences
stay outside the equality probes.
