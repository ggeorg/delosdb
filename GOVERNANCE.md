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
JDK 25 Class-File API generated-bytecode backend behind JavaFactory
RawStore-backed delos_mvcc SQL integration
one Derby RawStore persistence/recovery authority
MVCC transaction snapshots, visibility, indexes, maintenance, and vacuum
repository-integrity and permanent verification gates
DRDA server dependency hygiene and lifecycle hardening
optional DRDA virtual-thread worker mode
large DRDA EXTDTA temp spooling
stable-plan, EXPLAIN, EXPLAIN ANALYZE, and readable-engine diagnostics
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

MVCC is an access method over the same Derby RawStore used by the inherited heap. MVCC owns logical transaction/version semantics, including transaction identity, snapshots, visibility, conflicts, version chains, retention, maintenance, and vacuum. RawStore remains the sole physical persistence, logging, checkpoint, recovery, backup, and database-lifecycle authority.

Do not introduce a second MVCC file store, WAL, checkpoint stack, recovery authority, dual-write path, or runtime storage selector.

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
./gradlew clean releaseVerification :delosdb-storage-mvcc:check
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:derbyLanguageTests
./gradlew :delosdb-tests:delosFunctionalTests :delosdb-tests:delosConcurrencyTests :delosdb-tests:delosRecoveryTests
./gradlew :delosdb-tests:delosSystemTests :delosdb-server:compileJava delosServerStaticAnalysis
./gradlew s0CloseoutVerification
```

No release should present `delos_mvcc` as the default storage path until a separate promotion decision is made.
