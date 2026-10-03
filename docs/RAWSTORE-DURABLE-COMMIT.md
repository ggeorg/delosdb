# RawStore transaction-end durability coordination

## Status and source authority

This is a default-OFF implementation prototype, not a promoted storage behavior.
The source baseline is the supplied `delosdb 90.zip`, committed revision
`4c0c7345fc637a18b66fb7e205b2f55b1536a771`, including its working-tree changes.
No experiment has been silently promoted and no rejected control has been removed in this patch.

Enable the prototype before database boot with:

```text
-Ddelosdb.experimental.rawStoreDurableCommit.enabled=true
```

Optional evidence counters:

```text
-Ddelosdb.diagnostic.rawStoreDurableCommit=true
```

## What the source and prior experiment actually establish

`Xact.prepareCommit()` logs `EndXact`, calls `Logger.flush()` for synchronous
transaction completion, and only then allows normal post-commit completion.
`FileLogger.flush()` is also used for logged abort completion. `LogToFile.flush()`
already groups requests through `logBeingFlushed`, waits, and `lastFlush`.
It is incorrect to describe the inherited implementation as one unconditional
physical sync per transaction or as lacking group commit.

The R8 durable/no-sync comparison motivates work on synchronous persistence cost;
it does not identify a missing commit-coordinator algorithm by itself. The
`derby.system.durability=test` control affects more than just commit calls.
A throughput benefit from this coordinator remains a hypothesis until the same
binary is measured with the coordinator OFF and ON under normal durability.

## Responsibility and control flow

```text
Xact: log the same transaction-end decision, retain its completion ordering
    -> FileLogger.flush
        -> LogToFile.flushTransactionLog
            -> DurableCommitCoordinator: admit a durability demand
                -> one caller flushes a detached cohort's greatest LogInstant
                    -> existing LogToFile.flush(fileNumber, recordStart)
                    -> existing buffer/checksum/write/sync/log-switch path
                <- first unflushed byte from the existing RawStore watermark
            <- acknowledge exactly the covered requests
        -> inherited corrupt/freeze check
    -> existing transaction completion / lock release / MVCC publication
```

The coordinator owns only pending requests, leader handoff, and completion
notification. `LogToFile` still owns WAL ordering, bytes, the durable watermark,
I/O, corruption state, checkpoints, and recovery. No WAL record or page format
changes. There is no second persistence authority, executor, background thread,
new `Logger` interface method, or public production API.

Queued requests accumulate while the current leader performs I/O. The next
waiting caller becomes the next leader. A caller whose own request is complete
does not drain arbitrarily many later cohorts before returning.

No batching timer, sleep, spin phase, user delay knob, or minimum cohort size is
introduced. A lone caller flushes immediately. This avoids an intentional
single-client latency penalty; actual overhead still needs measurement.

## Correctness invariants

1. Requests refer to already-appended record starts. RawStore returns an
   **exclusive** first-unflushed byte; acknowledgement requires `recordStart < frontier`.
   Equality is insufficient. `LogCounter` ordering includes the log-file number.
2. The admission lock is never held during RawStore I/O. New requests may arrive
   while a cohort is flushing. Arrival order need not match LogInstant order.
3. A late request covered by RawStore's returned frontier can complete without
   another flush. The coordinator does not cache a separate durability watermark
   between cohorts or invent progress after a failed flush.
4. A failed cohort acknowledges nothing. A checked failure is retained for
   followers. An unexpected unchecked failure escapes unchanged to the leader;
   followers fail closed and further admission is poisoned. No automatic retry.
5. Interrupting a durability waiter does not turn an unflushed decision into
   success. Interruption is retained through Derby's `InterruptStatus` protocol.
6. A caller retaining the WAL monitor, or reentering from the active flush leader,
   bypasses cohort admission. It must never wait on another caller that needs a
   monitor or flush generation it owns.
7. Read-only, recovery, no-sync, and replication modes bypass coordination at
   admission. Existing lifecycle checks still govern the delegated flush; a role
   transition is not implemented by the coordinator.
8. Normal page flushes, checkpoint flushes, backup/freeze transitions, log-file
   switches, and `flushAll()` are not redirected through the request queue.
   XA prepare and logged abort requests using `FileLogger.flush()` retain their
   existing durability boundary; full XA/replication acceptance remains pending.

## Evidence and limits

The coordinator contract test exercises actual concurrent callers and controlled
flush completion. It covers detached cohorts, maximum-target selection,
leadership handoff, late requests, exclusive-frontier equality, log-file rollover,
checked/unchecked failures, interruption, reentrant admission, and concurrent
stress. It is not a substitute for running the storage engine.

The JDBC recovery tests create concurrent mixed Heap/MVCC transactions, wait for
every JDBC commit acknowledgement, then halt the subprocess without a clean
shutdown. One case adds no post-workload checkpoint; another checkpoints an
uncommitted transaction before halting. Recovery is performed with the coordinator
**disabled** and checks exact rows, payloads, rollback, PK uniqueness, subsequent
writes, and clean reopen. These are process-crash tests, not power-loss tests.
They do not certify replication, XA, all storage providers, or every retained
experimental Gen2 combination.

The benchmark-contract tests invoke the actual `Options` validator. They admit
both canonical arms, reject no-sync and rejected WAL-copy controls, and retain
the existing client-count/table-shape restrictions. They do not assert source
text, open sockets, launch Docker, or replace a real Gradle task execution.

Diagnostic lines start with `DELOS_RAWSTORE_DURABLE_COMMIT`. Counters are
**database-lifetime samples including setup and verification**, not measured-only
interval deltas. `cohorts` counts coordinator flush delegations, which may find
that RawStore has already flushed. `flushSyncPhases` counts the sync/write phase
inside `LogToFile.flush`, not operating-system syscalls or all append-time writes.
`maxCohort` counts requests acknowledged by one returned frontier. These scopes
must not be conflated with each other or with benchmark measured operations.

## Verification commands

From the repository root with JDK 25:

```bash
./gradlew \
  :delosdb-storage-derby:runDurableCommitCoordinatorTest \
  :delosdb-tests:runDelosRawStoreDurableCommitBenchmarkContract \
  :delosdb-tests:runDelosRawStoreDurableCommitRecovery \
  -Pdelosdb.sane=false --rerun-tasks --console=plain
```

Canonical comparison, using the same source/binary in both arms:

```bash
./gradlew \
  :delosdb-tests:runDelosRawStoreDurableCommitCanonical \
  -Pdelosdb.sane=false --rerun-tasks --console=plain
```

Both arms run PRIMARY_KEY_ONLY / INSERT_100, 128-byte payload, 8 clients,
8 repetitions, default WAL buffer, normal durability, and the full server matrix.
The existing page-validity/multi-insert-page/root/branch stack and Gen2-B
transaction-status control remain identical between arms. Only coordinator
ON/OFF changes. The Gen2 status control remains experimental; its presence in
both arms is not production acceptance of that control.

The task checks actual per-run server cohort evidence as well as configuration
markers. A green task establishes configuration and mechanism evidence, **not**
valid dispersion or a speedup. Retain IQR/median <=15% and max/min <=1.20, semantic
fingerprints, comparator normalization, and the established median policy. No
run trimming, threshold weakening, or performance claim from a mechanism marker.
A lone-caller contract test does not replace a single-client latency benchmark.
That sentinel and the full recovery/concurrency/backup/XA/replication matrix are
required before default promotion.

Reports are written under:

```text
build/reports/delosdb/benchmarks/rawstore-durable-commit/
    coordinator-off/
    coordinator-on/
```

## Coherent promotion and removal sequence

A coherent design does not require one giant class. Each existing owner should
retain its own invariant:

| Mechanism | Intended owner | Disposition before production promotion |
| --- | --- | --- |
| Transaction-end durability cohorts | RawStore logging | New prototype OFF; accept only with canonical benefit and lifecycle proofs |
| Known-page validity snapshot | FileContainer / allocation metadata | Keep OFF; prove invalidation across allocate, deallocate, reuse, recovery, and rollback |
| Bounded heap insert targets | FileContainer / heap insertion | Keep OFF; prove target lifecycle, allocation failure, rollback, and scan correctness |
| Root/branch insertion routing | B-tree access method | Keep OFF; prove route invalidation and authoritative latch/revalidation across split/grow/restart |
| Gen2 transaction-status visibility | MVCC logical visibility over RawStore records | Separate hardening: width-1 costs, status lifecycle, update/delete, retention and recovery |
| Preframed, flat-combined, concurrent-copy WAL controls | RawStore logging experiments | Remove rejected controls in a dedicated cleanup; retain historical reports |
| Split/latch/lock diagnostics | Test/diagnostic boundary | Retain only reusable diagnostics with clear ownership; retire campaign-only machinery |

First decide whether the coordinator earns its place. If it does not, remove it
rather than start tuning delays or queues. If it does, harden the durable boundary
and each retained shared-authority mechanism independently, then integrate their
configuration without leaving a collection of permanent experimental switches.
Do not promote the four shared-authority controls solely because the coordinator
passes its tests.

After that dedicated consolidation, move to the already-selected maintainability
sequence: benchmark contract ownership, Delos-owned aggregators, selected inherited
hotspots, security/dependency evidence, and targeted test-effectiveness evidence.
Do not refactor recovery or MVCC ownership merely to reduce file sizes.
