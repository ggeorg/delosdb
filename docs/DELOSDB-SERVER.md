# DelosDB Server

`delosdb-server` is the Derby-compatible DRDA network server. Its job is compatibility, not protocol reinvention.

## Compatibility rule

Do not replace DRDA/JDBC wire compatibility with Netty framing, gRPC, protobuf, JSON, or a new protocol in this module.

The server modernization strategy is:

```text
keep blocking DRDA semantics readable
clean dependency and scheduler seams
add optional modern threading support safely
harden large-stream behavior
preserve protocol compatibility
```

## Current green server improvements

```text
unused Lucene/json_simple compile-path noise removed from delosdb-server
server static gates added
resource-only DRDA localization package made module-visible
runtimeinfo no longer forces JVM GC
DRDA session scheduler isolated in DrdaSessionScheduler
scheduler behavior gate added
optional virtual-thread DRDA worker mode added
virtual-thread fairness audit added for queued-session dispatch
large EXTDTA values spool to temp storage above threshold
DelosDB-owned DRDA configuration centralized in DrdaServerConfiguration
```

## Threading mode

Default:

```text
platform threads
```

Optional virtual-thread worker mode:

```sh
-Ddelos.drda.threadMode=virtual
```

The listener/accept behavior and DRDA protocol semantics are not replaced by this option. The option only centralizes DRDA connection-worker thread creation behind the DelosDB threading seam.

The fairness audit is intentionally white-box and protocol-neutral: it enqueues a bounded set of synthetic DRDA sessions, dispatches one worker per session through the selected threading mode, and verifies that every queued session is selected exactly once with no duplicates, no missing sessions, and no leftover waiting sessions. The virtual mode proof additionally verifies that all workers used by the audit are virtual threads.

## EXTDTA spooling

Large non-streamed DRDA EXTDTA values spool to temporary storage instead of being fully buffered in heap.

Default threshold:

```text
1 MiB
```

Override:

```sh
-Ddelos.drda.extdta.spoolThresholdBytes=<bytes>
```

Small values keep the in-memory fast path. Large values use temp storage and cleanup-on-close/EOF behavior.

## Server static gates

The server static-analysis gate rejects:

```text
Lucene/json_simple imports in delosdb-server
MVCC implementation imports in delosdb-server
servlet imports outside NetServlet
forced GC from NetworkServerControlImpl.buildRuntimeInfo()
old EXTDTA heap-buffering path in DRDAConnThread
scattered delos.drda.* property parsing outside DrdaServerConfiguration
scattered virtual-thread factories outside DrdaThreading
```

Run:

```sh
./gradlew delosServerStaticAnalysis
```

Focused server verification:

```sh
./gradlew :delosdb-tests:delosSystemTests :delosdb-server:compileJava delosServerStaticAnalysis
```

## Not currently planned

```text
Netty rewrite
gRPC/protobuf/JSON DRDA replacement
Jakarta migration inside the existing NetServlet
Disruptor/event-pipeline rewrite
scheduler deletion before behavior is locked
```

A future Jakarta servlet adapter, if needed, should be separate from the legacy `javax.servlet` compatibility surface.

## Concurrent client stress

The mixed heap/MVCC DRDA concurrent-client stress proof runs through the Derby
network client with DelosDB virtual DRDA worker mode enabled. It creates one
heap table and one `using delos_mvcc` table, then runs concurrent clients that
perform committed heap updates, committed MVCC updates/inserts, rollback-only
work, read-only probes, and MVCC compress/vacuum.

The proof is intentionally protocol-preserving: it does not alter DRDA message
syntax, JDBC wire semantics, the accept loop, or transaction semantics. It only
locks the current runtime behavior under a heavier concurrent client shape.

Focused verification:

```sh
./gradlew :delosdb-tests:runDelosDrdaConcurrentClientStressTest
```
