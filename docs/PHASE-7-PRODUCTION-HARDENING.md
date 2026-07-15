# Phase 7 — Production Hardening Closeout

## Scope

This hardening slice closes the release blockers found during the Phase 7 static
review. It does not add a new isolation model, change the group-commit policy, or
redesign the page store.

## Closed blockers

### Post-commit-fence ambiguity

The commit route now stages every complete recoverable payload before the shared
transaction-status COMMITTED force. The database transaction status is correlated
explicitly with the independent page-volume transaction id.

The implementation now guarantees:

```text
no WAL or page-mutation ABORT after durable database COMMITTED
missing local outcome recovery through explicit status correlation
strict rejection of incomplete committed batches
live table fail-stop after committed publication failure
idempotent repair on reopen
```

### Ordered-index publication failure

A failed index rebuild no longer leaves a stale index available to live reads.
The table enters recovery-required state. Reopen reconstructs the index from the
committed row image.

### Maintenance failure semantics

Post-commit maintenance failure no longer changes a durable commit into a failed
transaction result. The failure is recorded in maintenance diagnostics.

### Maintenance shutdown

The database maintenance service now tracks `IDLE`, `QUEUED`, and `RUNNING`
separately. Only the worker that entered `RUNNING` clears it.

Shutdown now:

```text
stops and awaits the periodic scanner
waits for workers
cancels queued work after the graceful timeout
fails close if a running worker still ignores interruption
keeps table resources open until workers truly terminate
supports a later close retry after the worker exits
```

### Store close

Store close now attempts every table, aggregates failures, releases the backup
coordinator lease, and prevents new opens once shutdown begins. A maintenance
shutdown failure stops resource closure so an active worker cannot race closed
table files.

### Backup portability

Backup directory forcing now follows the common best-effort policy and treats
both unsupported directory channels and platform `IOException` as unsupported
rather than failing an otherwise valid backup publication.

### Long-reader validation

The long-reader buffer-pressure proof now respects second-touch admission: each
cold candidate is read once for bypass and again for admission before asserting
that replacement skipped the pinned reader page.

## Added or strengthened proofs

```text
prepared status reservation publishes no visibility
stale prepared status batch is rejected
failure before shared status force aborts safely
failure after shared status force never appends ABORT
missing local outcome recovers from explicit status correlation
transaction-id namespace collision does not mis-correlate recovery
subsystem recovery-record failure repairs on reopen
checkpoint failure repairs on reopen
ordered-index failure poisons the live table and rebuilds on reopen
post-commit maintenance failure preserves successful commit
stubborn maintenance worker prevents resource close
backup snapshot and close drain already enrolled commits
```

## Verification commands

Run from the repository root with JDK 25 and the repository Gradle wrapper:

```bash
./gradlew :delosdb-storage-mvcc:check
./gradlew :delosdb-storage-mvcc:runMvccPreparedCommitBatchTest
./gradlew :delosdb-storage-mvcc:runMvccTransactionGroupCommitHardeningTest
./gradlew :delosdb-storage-mvcc:runMvccDatabaseMaintenanceServiceTest
./gradlew :delosdb-storage-mvcc:runDelosMvccLongReaderValidation
./gradlew :delosdb-tests:runDelosMvccSerializableSemanticsTest
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew s0CloseoutVerification
```

The repository must continue to compile with Java release 25 and class-file
major version 69. No preview feature is introduced by this hardening slice.

## Isolation statement

This work does not change the Phase 7 SERIALIZABLE decision:

```text
SERIALIZABLE remains a transaction-snapshot compatibility mapping.
It does not prevent write skew and is not full serializability.
```

The executable SQL write-skew proof remains required.
