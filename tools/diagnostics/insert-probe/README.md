# Embedded insert probe

A harness-independent probe for F08-shaped concurrent inserts, used to find which
shared resource limits insert scaling and what work keeps it occupied. It runs one
embedded JVM per engine, so attribution does not depend on the cross-engine
benchmark harness or on throughput being stable on a laptop.

| File | Purpose |
|---|---|
| `F08InsertProbe.java` | N writers, disjoint ascending ids, 128-byte payload, commit every 100 rows; `bare`, `pk` or `indexed` (the cross-engine FULL_INDEXED shape); verifies committed contents |
| `run-insert-probe.sh` | Builds the probe and runs it against Delos (`build/libs`) or upstream Derby 10.17.1.0, optionally under JFR |
| `jfr_insert_attribution.py` | Writer monitor-wait time by monitor and site, page-latch waits by structure (heap, B-tree root, coupled B-tree child), and CPU work done while a heap page is latched |

```bash
. ~/Development/jdk25-env.sh
./gradlew jars :delosdb-tests:prepareUpstreamDerbyNetworkServerRuntime -Pdelosdb.sane=false
tools/diagnostics/insert-probe/run-insert-probe.sh delos 8 bare --rare-log-switch \
    --jfr build/tmp/insert-probe/delos-8-bare.jfr
tools/diagnostics/insert-probe/run-insert-probe.sh derby 8 bare --rare-log-switch
python3 -I tools/diagnostics/insert-probe/jfr_insert_attribution.py build/tmp/insert-probe/delos-8-bare.jfr
```

Options: `--rows N` (per writer, default 250000), `--rare-log-switch` (128 MB log
switch and checkpoint intervals, which removes nearly all switch/checkpoint syncs),
`--jfr OUT.jfr`, and `-- <JVM args>`, for example
`-- -Ddelosdb.storage.insertLanes.disabled=true` to compare against the single
shared insert page.

Caveats: absolute throughput on a laptop varies by 10–15% between batches; compare
engines and configurations within one batch. On macOS, Delos log-switch syncs use
`FileChannel.force(true)` (`F_FULLFSYNC`) while Derby uses `fsync`, so runs without
`--rare-log-switch` mostly measure that difference.
