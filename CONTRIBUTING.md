# Contributing to DelosDB

DelosDB is in a proof-driven modernization phase. The project accepts focused, compatibility-preserving changes that are backed by executable gates.

## Supported local workflow

Use the checked-in Gradle Wrapper from the repository root:

```sh
./gradlew build
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

Current focused gates:

```sh
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-tests:runDelosServerSchedulerTest :delosdb-server:compileJava delosServerStaticAnalysis
./gradlew s0CloseoutVerification
```

Full gate:

```sh
./gradlew clean fullVerification :delosdb-storage-mvcc:check
```

If a Derby test run was interrupted, run a clean verification from the repository root.

## Current project lanes

### MVCC storage lane

`delos_mvcc` is explicit and opt-in. Changes must preserve the default Derby-compatible heap path.

Current green MVCC storage work includes typed rows, overflow pages, page checksums, whole-page reuse, reusable-page index recovery, bounded page cache, page-record headers, slot accounting, and storage-layer static gates.

Do not change these boundaries casually:

```text
Derby heap format remains compatibility-locked.
DRDA/JDBC wire compatibility remains compatibility-locked.
JAVA_OBJECT / Derby UDT object values are rejected in delos_mvcc.
BLOB/CLOB are rejected in delos_mvcc until a deliberate LOB lifecycle design exists.
```

### Server lane

`delosdb-server` remains a Derby-compatible DRDA server. Server changes should preserve the protocol and compatibility model.

Current green server work includes dependency hygiene, no forced GC in runtimeinfo, isolated session scheduler, scheduler behavior gates, optional virtual-thread worker mode, EXTDTA temp spooling, and centralized DelosDB DRDA server configuration.

Do not replace DRDA with Netty, gRPC, JSON, protobuf, or a new wire protocol inside this module.

## Contribution rules

- Keep changes focused and source-backed.
- Add or update a smoke/proof when behavior changes.
- Preserve Derby-compatible heap behavior by default.
- Do not flip the global default store to `delos_mvcc`.
- Do not redesign DRDA/JDBC wire compatibility.
- Do not import MVCC implementation packages into `delosdb-server`.
- Do not add Java object serialization to `delosdb-storage-mvcc`.
- Do not add BLOB/CLOB support to `delos_mvcc` without a full lifecycle design and gate.
- Use Derby heap/raw-store patterns where useful, but do not lift Derby's log-coupled raw page layer wholesale into MVCC.
- Prefer small verified changes over mechanical rewrites.
- Update documentation after the code proof or planning decision is real.
- Do not remove Apache license headers or attribution.
- Do not use Apache Derby branding for modified DelosDB distributions.

## Documentation rules

Root-level Markdown should stay project-facing and current. Technical details belong under `docs/`.

Useful docs:

```text
docs/BUILDING.md
docs/DERBY-COMPATIBILITY.md
docs/MVCC-MISSION.md
docs/DELOSDB-SERVER.md
docs/sql-extensions.md
```

Avoid stale checkpoint documents. Update the maintained status docs instead.

## Workspace metadata

Developer workspaces may contain `.git/`, `.gradle/`, `.idea/`, build reports, and local databases. Cleanup scripts must not delete them. Overlay ZIPs and release artifacts must not include them.

## Style

Preserve inherited Derby style unless the cleanup is deliberate and behavior-preserving. Prefer small verified changes over broad rewrites.
