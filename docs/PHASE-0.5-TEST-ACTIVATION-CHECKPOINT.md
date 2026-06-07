# Phase 0.5 Test Activation Checkpoint

This checkpoint records the current boundary of inherited Derby test activation in DelosDB.

Phase 0.5 is not a return to Derby's old `derbyall` workflow. It is a Gradle-owned activation layer that recovers inherited test value through reviewed compile and execution islands.

## Status

Phase 0.5 is done enough to resume Phase 1 platform work.

The current test surface provides:

- broad compile-only activation for inherited Derby test sources;
- curated execution islands across the important runtime areas;
- full gate integration through `fullVerification`;
- explicit deferral of path-sensitive, message-sensitive, optimizer-plan-sensitive, old-harness, and runtime-heavy tails.

## Active execution coverage

The activated execution surface now covers:

- core engine smoke tests;
- memory and memorydb smoke tests;
- storetests smoke coverage;
- JDBC API smoke, statement, metadata, LOB, broader, and additional curated islands;
- JDBC4 smoke and broader curated islands;
- tools smoke coverage;
- lang basic smoke coverage;
- derbynet utility smoke coverage;
- i18n, performance, management, and large-data smoke islands.

This is intentionally not full-suite execution.

## Active compile coverage

Compile activation remains broader than execution activation. This is deliberate.

Compile-only islands include larger inherited batches for areas such as:

- lang;
- JDBC API tails;
- store;
- derbynet;
- compatibility and NIST sources.

These islands are useful because they keep source-level compatibility visible without forcing brittle runtime behavior into the gate prematurely.

## Deferred tails

The following categories remain intentionally deferred:

- old harness entry points and `_Suite` classes that assume Derby's historical runner model;
- `derbyall`-style orchestration;
- path-sensitive script tests and missing-resource tests;
- localization or exact-message-sensitive tests;
- optimizer-plan-sensitive tests;
- crash recovery, encryption, backup/restore, corruption, locking, and filesystem-sensitive store tests;
- network server tests that require process/socket lifecycle modernization;
- full lang, JDBC API, JDBC4, store, tools, or derbynet suite execution.

Deferred does not mean abandoned. It means each category needs its own modernization plan before joining execution.

## Required gates

Use these commands to validate the checkpoint:

```bash
./gradlew :delosdb-tests:listActivatedDerbyTestIslands
./gradlew :delosdb-tests:compileActivatedDerbyTests
./gradlew :delosdb-tests:runActivatedDerbySmokeExecutionIslands
./gradlew :delosdb-tests:runActivatedDerbyExecutionIslands
./gradlew fullVerification
chmod +x dev/modernization-audit.sh
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

## Exit decision

DelosDB can now resume Phase 1 platform work while preserving this Phase 0.5 checkpoint as the inherited behavior safety net.

The next Phase 1 work should stay narrow:

```text
internal extension registry
→ built-in provider registration concept
→ first experimental provider contract
```

Do not jump directly to storage replacement, optimizer rewrites, ServiceLoader plugin loading, or full SQL extension lifecycle.
