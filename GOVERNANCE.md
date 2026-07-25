# Governance

DelosDB is currently a maintainer-led fork.

## Decision principles

1. Derby-compatible behavior before novelty.
2. Working proofs before new architecture.
3. One behavior boundary per milestone.
4. Compatibility boundaries are explicit: heap format, SQL/JDBC behavior, and DRDA wire compatibility.
5. Benchmarks before performance claims.
6. Clear attribution to Apache Derby.

## Current project rule

DelosDB modernizes selected internals while keeping inherited Derby compatibility boring.

Closed or currently green areas include:

```text
Gradle-only developer workflow
ASM generated-bytecode backend isolated behind JavaFactory (transitional)
opt-in delos_mvcc SQL integration
MVCC typed durable row codec
MVCC overflow payload lifecycle
MVCC page checksums
MVCC whole-page reuse and reusable-page index recovery
MVCC page cache lifecycle and bounded eviction
MVCC page-record headers and slot accounting
MVCC/server static closeout gates
DRDA server dependency hygiene
DRDA scheduler seam and behavior gate
optional DRDA virtual-thread worker mode
large DRDA EXTDTA temp spooling
```

Default behavior remains Derby-compatible heap storage. `delos_mvcc` remains explicit or property-gated.

## Compatibility rule

Do not change these boundaries without an explicit milestone and compatibility decision:

```text
Derby heap/raw-store disk compatibility
DRDA/JDBC wire compatibility
JDBC public behavior
Apache Derby attribution and licensing
```

DelosDB may add explicit opt-in SQL or runtime behavior, but default behavior should remain compatible.

## MVCC rule

MVCC can reuse Derby heap/raw-store patterns and self-contained services where useful:

```text
typed DataValueDescriptor codec
long-row/overflow patterns
page checksums
cache/buffer-pool ideas
free-space/allocation-page ideas
slotted-page/record-header ideas
```

But MVCC must not import Derby's log-coupled raw page layer wholesale. MVCC durable pages, overflow pages, reusable-page metadata, page cache, and record headers are MVCC-owned formats.

## Server rule

`delosdb-server` remains a Derby-compatible DRDA server. Modernization should be compatibility-safe:

```text
clean dependencies
isolate scheduler behavior
add optional virtual-thread support behind a configuration seam
harden large stream handling
keep NetServlet compatibility quarantined
```

Do not replace DRDA with Netty, gRPC, JSON, protobuf, or another protocol inside the compatibility server.

## Release rule

No release should be cut unless these gates are green:

```sh
./gradlew clean fullVerification :delosdb-storage-mvcc:check
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-tests:runDelosServerSchedulerTest :delosdb-server:compileJava delosServerStaticAnalysis
./gradlew s0CloseoutVerification
```

No release should present `delos_mvcc` as the default storage path until a separate promotion decision is made.
