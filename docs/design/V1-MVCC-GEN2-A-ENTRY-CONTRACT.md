# MVCC Gen2-A entry contract

## Status

APPROVED ENTRY GATE

MVCC Gen1 is frozen as a runnable semantic/reference implementation. Performance work proceeds through
Gen2 candidates rather than incremental Gen1 topology tuning. Gen1 remains available for differential
correctness until Phase 13 consolidation.

## Evidence requiring Gen2

The Phase R2 mutation physical-amplification ledger established that one logical INSERT in Gen1 performs
materially more physical work than Heap: two current/user records instead of one, 3-4x RawStore page
writes, about 3.4-3.6x WAL span, and for PK INSERT both a native MVCC ordered-index entry and the inherited
SQL PK index entry.

Gen2 therefore starts from physical-work constraints, not from a throughput micro-optimization.

## Important correction: publication ceiling versus transaction identity

The database-wide metadata page has multiple responsibilities and they must not be conflated.

With the default concurrent commit-publication protocol:

* commit sequences are durably reserved in blocks (default 64);
* reserving a block advances the recovery publication ceiling to the block end;
* subsequent commits within the block publish through the in-memory ordered publication frontier;
* `RECOVERY_PUBLICATION_CEILING_FIELD` is therefore not durably rewritten on every default-path commit.

The existing identity contract proves this directly: after the first committing writer reserves 1..64,
a second committing writer leaves both `NEXT_COMMIT_SEQUENCE_FIELD` and
`RECOVERY_PUBLICATION_CEILING_FIELD` unchanged.

The actual per-writing-transaction database-wide durable reservation in Gen1 is the transaction ID:
`reserveTransactionId()` updates `NEXT_TRANSACTION_ID_FIELD` through a nested top transaction and forces
that reservation before the user mutation proceeds.

Gen2 must therefore solve two different questions:

1. preserve crash-safe publication without introducing a per-commit global durable-page update;
2. eliminate or amortize the per-writing-transaction global durable transaction-ID reservation.

Block/range reservation, RawStore-derived identity, or another RawStore-recoverable scheme may be used,
but RawStore remains the sole durability and recovery authority.

## Gen2-A scope

The first vertical slice is deliberately BARE-table only:

* CREATE TABLE
* INSERT
* COMMIT
* current point read
* ROLLBACK
* shutdown/reopen
* recovered current read

No PK, secondary index, historical scan, vacuum, or optimizer work is added until this slice passes its
physical-work gate.

## Gen2-A physical invariants

1. RawStore remains the only WAL, undo, recovery, checkpoint, and physical persistence authority.
2. A fresh logical row has one authoritative current physical row representation.
3. A first INSERT does not create a separate history payload merely because future history may exist.
4. Current visibility and current payload are local or near-local; no mandatory directory->version discovery.
5. No database-wide durable page is rewritten for every commit solely to publish visibility.
6. Database-wide identities are reserved/amortized so a tiny writer does not require a forced global metadata
   reservation on every transaction where an equivalent crash-safe scheme can avoid it.
7. Gen1 remains runnable and supplies the SQL semantic oracle during Gen2 development.
8. Every Gen2-A candidate is measured immediately with the same physical ledger before wider feature work.

## Gen2-A kill gate

Reject/redesign the candidate before adding PK/history if a fresh BARE INSERT still requires the Gen1 shape:

* two current/user records, or
* 3-4 dirty RawStore data/metadata pages per logical row in steady state, or
* approximately 3x Heap WAL span without a correctness requirement that explains the work.

The target is not byte-for-byte Heap equivalence. MVCC may require additional metadata, but every additional
physical operation must have a named MVCC correctness purpose and must be bounded/amortized.
