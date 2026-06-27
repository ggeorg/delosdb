# MVCC static-analysis hardening

This note tracks the hardening work from the DelosDB 58 static-code review of fork-new MVCC code.

## Addressed in MODULE20E

### Native deserialization guard

`MvccInheritedRowCodec` still exists as a compatibility codec for inherited Derby `StoreDataValue[]` rows, but its decode path now installs a JEP 290 `ObjectInputFilter` before `readObject()`.

The filter allows the expected store-value row shape and narrowly required value-support classes. Unexpected serialized classes are rejected before the deserialized object can be used.

Longer-term direction: replace this inherited compatibility codec with an explicit durable row codec when the inherited Derby row representation is no longer needed on this path.

### Atomic row-directory rewrite

`MvccRowDirectoryStore.rewriteHeads()` now requests `StandardCopyOption.ATOMIC_MOVE` for the rewrite-file swap and keeps the previous same-directory `REPLACE_EXISTING` path only as a documented fallback for filesystems that do not expose atomic move through the JDK.

The rewrite file is forced before the move and the parent directory is forced afterwards when the platform supports it.

### Transaction-table compaction

`MvccTransactionManager` no longer keeps all historical transactions in the active transaction table. Active transactions are held separately from terminal outcomes. Terminal outcomes are compacted once they are behind the retained snapshot watermark and no active transaction can still need their exact per-transaction state.

The transaction catalog still preserves visibility semantics for compacted committed and aborted outcomes.

## Tracked but not changed in this pass

### WAL/status append throughput

`MvccLogWriter`, `MvccTransactionStatusStore`, and `MvccRowDirectoryStore` still use conservative force-per-append behavior. This is correct but throughput-hostile.

The future performance lane should replace this with lifecycle-owned append channels and explicit group-commit/fdatasync policy. That should be done as a separate durability/performance overlay, not mixed with security hardening.

### Per-table coarse monitor

The coarse `synchronized` table/chain model remains intentional for the correctness phase. Row-level or partition-level concurrency belongs after the modern RDBMS model and MVCC correctness lanes are stable.
