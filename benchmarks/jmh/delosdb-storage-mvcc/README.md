# DelosDB MVCC JMH storage benchmarks

This is a standalone, opt-in Gradle build for DelosDB-owned MVCC storage
algorithms. It is outside the normal DelosDB build, so normal module checks,
S0, SQL integration, and runtime artifacts do not resolve or package JMH.

The build uses:

* JDK 25 toolchain and `--release 25`;
* JMH 1.37;
* `me.champeau.jmh` 0.7.3;
* composite-build substitution to the local `delosdb-storage-mvcc` project;
* three warmup iterations, five measurement iterations, and two forks;
* machine-readable JSON output.

Benchmarks currently cover:

* `DelosMvccPageCodecBenchmark` — MVCC page-record and overflow-chunk codecs.
* `DelosMvccOrderedIndexBenchmark` — ordered-index equality and range lookup.
* `DelosMvccBufferCacheBenchmark` — MVCC page-cache read, pin/unpin,
  dirty-write, and replacement-pressure paths.

From the DelosDB repository root, compile and run the full benchmark matrix:

```bash
./gradlew -p benchmarks/jmh/delosdb-storage-mvcc clean jmh
```

Run only selected benchmark classes or methods:

```bash
./gradlew -p benchmarks/jmh/delosdb-storage-mvcc clean jmh \
  -Pdelosdb.jmh.includes='.*DelosMvccOrderedIndexBenchmark.*'
```

Results are written to:

```text
benchmarks/jmh/delosdb-storage-mvcc/build/results/jmh/delosdb-storage-mvcc.json
```

Validate the repository boundary and deterministic built-in baseline without
resolving JMH:

```bash
./gradlew delosJmhStorageBenchmarkAdapter
```

The benchmark build is intentionally not included by the root `settings.gradle`.
JMH remains an explicit performance-validation dependency and never enters
DelosDB runtime, module checks, or S0 paths.
