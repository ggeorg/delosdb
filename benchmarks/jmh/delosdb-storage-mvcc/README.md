# DelosDB MVCC JMH storage benchmarks

This directory contains opt-in JMH benchmark source for DelosDB-owned MVCC
storage algorithms. It is intentionally outside normal Gradle source sets so S0,
module checks, and SQL integration tests do not require JMH dependencies.

Benchmarks currently cover:

* `DelosMvccPageCodecBenchmark` — MVCC page-record and overflow-chunk codecs.
* `DelosMvccOrderedIndexBenchmark` — ordered-index equality and range lookup.
* `DelosMvccBufferCacheBenchmark` — MVCC page-cache read, pin/unpin, dirty-write, and replacement-pressure paths.

Run the built-in deterministic baseline and write the adapter report:

```bash
./gradlew delosJmhStorageBenchmarkAdapter
```

Run the same adapter with an approved external JMH command supplied by CI or a
release-validation job:

```bash
./gradlew delosJmhStorageBenchmarkAdapter \
  -Pdelosdb.jmh.storage.command="<compile-and-run JMH command>"
```

The external command is intentionally not embedded in the repository because
JMH dependency versions, classpath construction, result format, and runtime JVM
flags belong to the explicit validation lane that opts into benchmarking.
