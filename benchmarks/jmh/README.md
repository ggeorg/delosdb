# DelosDB standalone JMH build

This directory is an independent Gradle build. It is deliberately absent from
the repository root `settings.gradle`, normal `check`, S0, and runtime module
configuration.

The benchmark consumes assembled DelosDB runtime jars and exercises only public
JDBC behavior. It does not import package-private MVCC classes, Derby
implementation packages, root test source sets, or a benchmark-only production
API.

The initial benchmark matrix contains:

- prepared primary-key lookup;
- prepared secondary-index equality lookup;
- prepared composite range scan;
- prepared full scan;
- prepared aggregate;
- empty commit;
- empty rollback.

Statement preparation and fixture construction occur outside measurement. Read
operations execute repeatedly inside the iteration transaction; rollback occurs
in iteration teardown, outside the measured benchmark method. Commit and
rollback have separate benchmark methods. Mutating SQL operations remain in the
JUnit benchmark lane until they can be restored without hiding work inside a
JMH invocation boundary. Low-level cache and codec JMH benchmarks are likewise
not added until a stable production benchmark SPI exists.

## Build the runtime artifacts

From the repository root:

```bash
./gradlew jars
```

## Compile the standalone benchmark

```bash
./gradlew -p benchmarks/jmh clean check
```

## Smoke run

```bash
./gradlew -p benchmarks/jmh clean jmh \
  -Pdelosdb.jmh.includes=.*primaryKeyLookup \
  -Pdelosdb.jmh.providers=heap,mvcc \
  -Pdelosdb.jmh.rows=100 \
  -Pdelosdb.jmh.payloadSizes=128 \
  -Pdelosdb.jmh.commitBatchSizes=100 \
  -Pdelosdb.jmh.warmupIterations=1 \
  -Pdelosdb.jmh.iterations=1 \
  -Pdelosdb.jmh.warmupTime=250ms \
  -Pdelosdb.jmh.iterationTime=250ms \
  -Pdelosdb.jmh.forks=1
```

## Normal standalone run

```bash
./gradlew -p benchmarks/jmh clean jmh
```

Default parameters are intentionally bounded:

- providers: `heap,mvcc`
- rows: `100`
- payload size: `128`
- fixture commit batch: `100`
- warmup iterations: `2`
- measurement iterations: `3`
- forks: `1`
- threads: `1` (fixed; concurrency belongs to the stress/jcstress lanes)

Use comma-separated project properties to expand a deliberate run, for example:

```bash
./gradlew -p benchmarks/jmh clean jmh \
  -Pdelosdb.jmh.rows=100,1000 \
  -Pdelosdb.jmh.providers=heap,mvcc \
  -Pdelosdb.jmh.forks=2 \
  -Pdelosdb.jmh.warmupIterations=5 \
  -Pdelosdb.jmh.iterations=10 \
  -Pdelosdb.jmh.warmupTime=1s \
  -Pdelosdb.jmh.iterationTime=1s
```

## Reports

- `benchmarks/jmh/build/reports/jmh/results.json`
- `benchmarks/jmh/build/reports/jmh/human.txt`

JSON is the required machine-readable result. The human report is supplemental.

## Supported properties

- `delosdb.jmh.includes`
- `delosdb.jmh.providers`
- `delosdb.jmh.rows`
- `delosdb.jmh.payloadSizes`
- `delosdb.jmh.commitBatchSizes`
- `delosdb.jmh.warmupIterations`
- `delosdb.jmh.iterations`
- `delosdb.jmh.warmupTime`
- `delosdb.jmh.iterationTime`
- `delosdb.jmh.forks`
- `delosdb.jmh.timeout`
- `delosdb.jmh.verbosity`
- `delosdb.jmh.profilers`
- `delosdb.jmh.jvmArgs`

The build pins JMH `1.37` and the Gradle JMH plugin `0.7.3`.
