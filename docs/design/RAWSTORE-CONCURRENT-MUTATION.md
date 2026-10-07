# RawStore Concurrent Mutation (RCM) Architecture

**Status:** Accepted architecture; RCM-0/1 qualification implementation in progress  
**Scope:** DelosDB RawStore / Derby-compatible Heap and B-tree mutation path  
**Primary workload motivating the design:** concurrent `PRIMARY_KEY_ONLY INSERT_100`  
**Source basis:** current reconstructed DelosDB 91 + accepted transaction-status lifecycle overlays, PostgreSQL `master`, MariaDB `server-main`, and existing F08/JFR evidence.  
**Important:** This document is a design. It deliberately does not introduce production code or a new benchmark branch.

---

## 1. Executive decision

The F08 evidence no longer supports another isolated lock, allocation, page-target, routing, or WAL micro-optimization.

The shared Derby-family write deficit is best explained by a **mutation contract that repeatedly forces each physical record mutation through the same generic page-operation and WAL machinery while acquiring exclusive page ownership early**. A primary-key insert pays that machinery once for the heap/current row and again for the B-tree entry.

The target architecture is therefore **RawStore Concurrent Mutation (RCM)**:

1. prepare immutable mutation payloads outside exclusive page ownership;
2. keep the current exclusive `BasePage` contract for compatibility, but shorten the time spent under it;
3. use validated immutable B-tree routing hints rather than retrofitting generic read/write page latches in the first generation;
4. let RawStore reserve WAL order separately from copying WAL payload bytes;
5. retain the existing on-disk page format, WAL format, logical undo, recovery, group flush, and single RawStore authority in the first generation;
6. make the new path opt-in/capability-based internally, with the existing mutation path as the correctness fallback during qualification;
7. judge the architecture once as an integrated write-path change, not as a sequence of permanent experiment flags.

RCM is **not** a second storage engine, a second WAL, a Gen2-only index, or a clustered-storage rewrite.

---

## 2. Problem statement

The fundamental F08 scaling result is the 1/2/4/8-client divergence:

| Clients | Derby PK INSERT_100 | PostgreSQL PK INSERT_100 |
|---:|---:|---:|
| 1 | ~85.5k | ~111.8k |
| 2 | ~120.6k | ~214.2k |
| 4 | ~159.5k | ~369.1k |
| 8 | ~141.9k | ~476.8k |

At one client Derby is materially but not catastrophically behind. At eight clients it stops converting more writers into throughput while PostgreSQL continues scaling.

A second strong observation is the incremental primary-key cost at eight clients. Representative clean campaigns show approximately:

| Engine | BARE INSERT_100 | PK INSERT_100 |
|---|---:|---:|
| Derby | ~208.7k | ~122.0k |
| Delos Heap | ~208.8k | ~117.5k |
| PostgreSQL | ~414.9k | ~380.8k |

Using inverse aggregate throughput only as a decomposition aid—not literal per-operation latency—the PK increment is roughly 3.4–3.7 µs equivalent for the Derby family and ~0.22 µs equivalent for PostgreSQL.

The important conclusion is qualitative: **a Derby B-tree entry adds a large fraction of another generic physical-record mutation, while PostgreSQL's index insertion adds much less relative overhead.**

---

## 3. What the existing evidence has already ruled out

The design must not reopen these as primary F08 answers:

- insert-page fanout: real, ~+4.6% normalized, secondary;
- B-tree root/branch routing: real, roughly 10%-class, secondary;
- B-tree key-prefix materialization: effectively no normalized movement;
- RawStore page-header cache growth: allocation reduction without throughput movement;
- SQL multi-row shape: ~1–3% class;
- previous-key/RecordId locking: millions of acquisitions but effectively zero logical wait / entry-mutex contention in the measured campaign;
- split herd: negligible redundant split activity;
- WAL preframing: rejected;
- WAL flat combining: reduced visible monitor contention without architecture-sized throughput gain;
- concurrent WAL payload-copy prototype: achieved overlap but did not cure the scaling collapse;
- outer durable-commit coordinator: real cohorts, ~2% class, rejected;
- transaction-status visibility as the shared F08 solution: rejected; its value is Gen2 commit-restamp removal and lifecycle correctness.

These experiments are useful constraints. RCM must attack a broader mutation lifetime/ownership problem rather than repackage one of them.

---

## 4. Current Delos/Derby mutation path: source facts

### 4.1 Heap insert

`HeapController.doInsert(...)` selects an insert page and calls the generic RawStore page insertion path. The statement-level heap controller is already retained across the statement; controller reopen per row is not the shared Derby bottleneck.

The important physical path is:

```text
HeapController.doInsert
  -> BasePage.insertAtSlot / insertNoOverflow
  -> LoggableActions.actionInsert
  -> InsertOperation
  -> Xact.logAndDo
  -> FileLogger.logAndDo
  -> LogToFile.appendLogRecord
  -> InsertOperation.doMe
  -> StoredPage.storeRecord / storeRecordForInsert
```

### 4.2 B-tree insert

`BTreeController.doIns(...)` finds the insertion leaf, enforces duplicate/previous-key semantics, and ultimately calls the same generic RawStore `Page.insertAtSlot(...)` machinery for the index entry.

Normal traversal uses `ControlRow.get(...)` and the RawStore page latch. `BasePage` currently models a single exclusive page owner. `ControlRow` is attached as page auxiliary state under that ownership model.

This means a generic read/write-latch retrofit is not a safe first RCM step.

### 4.3 Insert record preparation

`InsertOperation` already serializes the row into its optional WAL data once using `StoredPage.logRow(...)` and a transaction-local reusable byte buffer.

This invalidates a simplistic claim that the same Java row is fully serialized twice.

However, runtime `InsertOperation.doMe(...)` passes the WAL optional data back to `StoredPage.storeRecord(...)`, which parses field headers and data and writes the physical on-page representation under the page latch.

The WAL record representation and the on-page representation are **not byte-identical**. `StoredFieldHeader.FIELD_FIXED`, for example, is a log-only encoding property; on-page lengths are compressed. Therefore RCM must not assume the WAL payload can simply be `memcpy`'d into the page.

### 4.4 WAL ownership

`FileLogger` is private to each RawStore transaction. Its synchronized `logAndDo(...)` method is therefore not the cross-writer global authority.

`LogToFile.appendLogRecord(...)` is the shared ordering authority. It serializes shared log-position/checksum/file-switch state and copies the record into the RawStore log buffer.

`LogAccessFile` already buffers WAL in memory. The problem is not one OS write per logical record.

### 4.5 Existing group flush

`LogToFile.flush(...)` already implements inherited group-flush behavior using `logBeingFlushed` / `lastFlush`. RCM must use this rather than add an outer commit coordinator.

### 4.6 WAL-before-data

`CachedPage.writePage(...)` ensures the page's `lastLogInstant` is flushed before the page is written. This ordering is non-negotiable.

---

## 5. Cross-engine architectural contrast

### PostgreSQL

Relevant source behavior:

- heap tuple preparation occurs before exclusive target-buffer ownership;
- heap target selection is backend-local / FSM-assisted;
- B-tree internal traversal uses read buffer locks, with write ownership concentrated at the leaf;
- the rightmost insertion fast path uses a backend-local cached leaf and conditional acquisition, abandoning the shortcut on contention;
- `_bt_insertonpg()` directly inserts a prepared `IndexTuple` using `PageAddItem()`;
- WAL insertion separates ordering/reservation from parallel payload population; PostgreSQL currently uses multiple WAL insertion locks (`NUM_XLOGINSERT_LOCKS = 8`).

The design lesson is **not** to copy PostgreSQL APIs. It is to keep work writer-local for longer and narrow exclusive shared ownership toward the actual mutation.

### MariaDB / InnoDB

Relevant source behavior:

- primary-key storage is clustered for this workload, avoiding a separate heap + PK tuple;
- B-tree insertion attempts an optimistic leaf modification before pessimistic structural work;
- mini-transactions group page-latch and redo responsibilities;
- log-buffer space is reserved atomically and populated independently;
- write/flush coordination is distinct from the logical row operation.

Clustering is not required for DelosDB because PostgreSQL demonstrates strong scaling with separate heap and index storage. The useful lesson is again **short, local mutation ownership plus concurrent log-buffer population**.

---

## 6. RCM design invariants

Every implementation decision must preserve these invariants.

### 6.1 Persistence authority

There remains exactly:

- one RawStore WAL;
- one RawStore crash-recovery authority;
- one undo/CLR authority;
- one physical page persistence authority.

RCM may change how a mutation is prepared and fed into RawStore. It must not create a parallel persistence subsystem.

### 6.2 First-generation format compatibility

RCM generation 1 keeps:

- current on-disk heap page layout;
- current on-disk B-tree page layout;
- current RawStore WAL record format;
- existing `InsertOperation` identity and recovery compatibility where feasible;
- current logical undo callbacks and CLR behavior.

Format changes require a separate later proposal and are not necessary to prove the concurrency architecture.

### 6.3 Correctness fallback

If a row contains streams/overflow behavior or another unsupported condition, RCM falls back to the existing `Page.insertAtSlot(...)` path. Unsupported cases must never silently take a weaker mutation path.

### 6.4 Failure semantics

A failure after WAL reservation/copy or during page apply must preserve current RawStore corruption/abort semantics. No reserved-but-unpublished WAL gap may be treated as committed or flushable.

### 6.5 No benchmark-specific mode

The production design is not gated by `INSERT_100`, payload width, a hardcoded page count, or an F08-only property.

---

## 7. Target architecture

```text
SQL / Heap / B-tree access method
        |
        |  writer-local
        v
PreparedMutationPayload
        |
        |  candidate page/routing lookup
        v
short validation + exclusive target-page latch
        |
        +--> bind recordId / slot / page-specific header
        |
        v
PreparedPageMutation
        |
        +--> RawStore WAL reservation/order
        |       |
        |       +--> concurrent copy into reserved log-buffer range
        |       +--> publish contiguous completed WAL frontier
        |
        +--> direct runtime page apply using prepared page image
        |
        v
release page latch
        |
        v
existing Xact commit / existing LogToFile group flush
        |
        v
existing recovery / undo / page cleaner
```

RCM has three coordinated components:

1. **Prepared mutation** — reduce work performed while the target page is exclusively owned.
2. **Validated routing / short page ownership** — avoid turning generic RawStore page latching into a new R/W latch system in generation 1.
3. **Concurrent WAL reservation/copy** — reduce shared log-order critical-section duration only after the mutation path can exploit it.

These components are designed together because previous experiments showed that optimizing one shared authority simply exposes the next.

---

## 8. Component A — Prepared mutation payload

### 8.1 Goal

Move value encoding, length computation, and immutable payload ownership out of the exclusive target-page interval wherever possible.

### 8.2 Proposed internal types

Names are illustrative and should be adjusted to repository conventions.

```java
interface PreparedRawMutation {
    int estimatedWalBytes();
    boolean supportsDirectApply();
}

final class PreparedRowPayload implements PreparedRawMutation {
    // immutable owned field payloads / status metadata
    // no alias to caller-mutated StoreDataValue state
}

final class BoundPageInsert {
    // page/slot/recordId-specific transient runtime representation
    // legacy WAL optional data
    // transient page-native record image or field layout
}
```

### 8.3 Two-stage preparation

**Stage 1 — before target-page latch**

Create an immutable, transaction-owned `PreparedRowPayload`:

- snapshot/serialize application values;
- compute null/status/field data;
- compute exact or conservative size bounds;
- never retain mutable caller-owned `StoreDataValue` aliases;
- reject/fallback for unsupported streaming/overflow cases.

**Stage 2 — while target page is latched**

Bind page-specific state:

- allocate/validate `recordId` using existing page rules;
- choose/validate slot;
- verify free space / overflow requirements;
- generate page-specific record header;
- generate current-format WAL optional data;
- produce a transient page-native representation for normal runtime apply.

This stage must be small and bounded.

### 8.4 Direct runtime apply

Current recovery continues to deserialize the existing durable WAL payload through the canonical path.

For a normal in-process mutation, `InsertOperation` may additionally carry a **transient, non-serialized** prepared page image. `doMe(...)` uses it when present; recovery-created operations do not have it and use the existing stream-based path.

Conceptually:

```text
normal execution:
  PreparedRowPayload
    -> BindPageInsert
    -> durable legacy WAL bytes
    -> transient page-native bytes
    -> log
    -> direct page apply

crash recovery:
  durable legacy WAL bytes
    -> existing InsertOperation recovery decode/apply
```

This preserves WAL compatibility while allowing the common runtime path to skip repeated field-header interpretation/re-encoding under the target-page latch.

### 8.5 Required preservation points

The direct apply path must execute the semantic equivalent of:

- `page.preDirty()` before logging;
- `StoredPage.logAction(instant)` / page versioning;
- free-space accounting;
- slot-table shift/update;
- record header and `recordId` semantics;
- reserved-space behavior;
- auxiliary B-tree invalidation callbacks;
- logical undo registration and CLR behavior.

The existing implementation remains the executable specification until equivalence tests prove the direct path.

---

## 9. Component B — page ownership and B-tree routing

### 9.1 What RCM does not do initially

Do **not** change `BasePage` from single-owner exclusive latching to a generic reader/writer latch in generation 1.

Reasons:

- the current `owner` model assumes one `BaseContainerHandle`;
- latch acquire/release participates in observer/abort semantics;
- B-tree `ControlRow` is attached as mutable auxiliary page state;
- page read routines have not yet been proven side-effect free under concurrent readers;
- a repository-wide R/W latch retrofit would combine too many correctness risks with the mutation change.

### 9.2 Generation-1 routing approach

Generalize the existing immutable root/branch routing-snapshot idea into an internal **validated routing view**, but do not treat it as authority.

```text
writer-local / immutable BTreeRoutingView
        |
        v
candidate leaf page
        |
        v
try exclusive leaf latch
        |
        +-- unavailable -> abandon hint, canonical path
        |
        v
validate generation + key range + page role
        |
        +-- invalid -> release, canonical path
        |
        v
mutate leaf
```

Properties:

- hint only; canonical tree search remains correctness authority;
- structural mutation invalidates generations through existing page-change hooks;
- contention causes fallback rather than wait on the cached candidate;
- no correctness state resides only in the snapshot;
- no persistent format changes.

Root/branch routing alone previously produced only ~10%-class gains, so this is not presented as the F08 solution. Its role in RCM is to keep generic internal-page exclusive ownership out of the common path once the prepared mutation and WAL path can exploit the reduced critical section.

### 9.3 Future generation

Only after RCM generation 1 is proven should DelosDB consider an explicit RawStore read-latch API for B-tree internal pages. That would require an independent proof of read-side immutability and `ControlRow` lifecycle redesign.

---

## 10. Component C — concurrent WAL reservation and copy

### 10.1 Why the previous prototype is insufficient

The rejected concurrent WAL-copy experiment proved that overlapping payload copies alone did not solve F08 while upstream page/access bottlenecks remained.

RCM revisits WAL concurrency only as one part of the integrated mutation architecture.

### 10.2 Required state separation

The current append path conceptually exposes one advancing log end. Concurrent reservation needs three distinct frontiers:

```text
reservedEnd   >= publishedEnd >= durableEnd
```

- **reservedEnd**: byte space has been assigned an ordering position;
- **publishedEnd**: all bytes up to this position are completely populated and safe for a flusher to consume;
- **durableEnd**: bytes are known durable according to current RawStore flush semantics.

A later reservation must never allow `publishedEnd` to jump over an incomplete earlier reservation.

### 10.3 Proposed internal contract

```java
final class LogReservation {
    LogInstant instant();
    long start();
    long end();
    // owned destination slices / buffer generation
}

LogReservation reserve(int bytes, LogRecordMetadata metadata);
void copy(LogReservation reservation, ByteBuffer source);
void publish(LogReservation reservation);
```

Exact API names are secondary. The important separation is ordering vs payload copy.

### 10.4 Reservation critical section

The short shared reservation section owns only state that genuinely must be globally ordered:

- log record instant / byte position;
- buffer/file rollover decisions;
- checksum-block placement metadata;
- any FIRST/LAST/checkpoint ordering rules that cannot be relaxed;
- replication/encryption buffer-generation selection where applicable.

It must **not** copy the full mutation payload while holding the global ordering monitor.

### 10.5 Copy and publication

Writers copy into disjoint reserved ranges concurrently.

Publication tracks completion. If reservations A, B, C were ordered and B/C finish before A:

```text
reserved:   A B C
completed:    B C
published:  before A

A completes
published advances through A, B, C
```

Flush may not consume through an unpublished gap.

### 10.6 Capacity/backpressure before page ownership

A crucial RCM rule is: **do not acquire a target-page exclusive latch and then block indefinitely waiting for WAL-buffer capacity.**

Preparation computes a conservative WAL-size bound. The transaction obtains a log-capacity permit/credit before entering the target-page critical section where practical. Binding then converts that credit into the exact reservation.

If exact capacity cannot be guaranteed for a mutation class, that class initially uses the legacy append path rather than risking a page-latch convoy.

### 10.7 Existing group flush remains

`LogToFile.flush(...)`, `logBeingFlushed`, `lastFlush`, and existing sync/group-commit behavior remain the durability coordinator.

No outer coordinator is introduced.

### 10.8 Failure behavior

- A failed reservation/copy must wake any waiter whose publication/flush frontier depends on it.
- Failure after the log position is externally observable is fatal to the owning RawStore log generation unless existing semantics provide an exact rollback mechanism.
- No page may be written whose `lastLogInstant` exceeds `durableEnd`.
- `CachedPage.writePage(...)` WAL-before-data ordering remains authoritative.

---

## 11. Interaction with transaction begin/end and checkpoints

`FileLogger.logAndDo(...)` currently gives FIRST/LAST transaction records stronger coordination with the log factory for transaction-table/checkpoint correctness.

RCM must preserve that distinction.

Generation 1 should use:

- **normal row/index mutations:** concurrent reservation/copy path;
- **FIRST/LAST/checkpoint-sensitive records:** conservative legacy ordering fence unless source proof shows a safe relaxation;
- **recovery/log switch/checkpoint operations:** legacy exclusive coordination initially.

This prevents the first implementation from redesigning checkpoint semantics at the same time as ordinary DML mutation.

---

## 12. Logical undo and B-tree correctness

A primary-key insert is not only bytes on a page. The current B-tree path relies on:

- duplicate detection;
- previous-key locking / serializable semantics;
- logical undo callbacks;
- page split relocation handling;
- CLR generation;
- page-generation / structural invalidation.

RCM does not bypass any of these.

The access method still performs all logical checks before requesting the physical prepared mutation. `LogicalUndo` remains attached to the durable operation. If a split/retry changes the target page, the immutable Stage-1 payload is reused but Stage-2 page binding is discarded and rebuilt for the new page.

---

## 13. Heap compatibility and Gen2 relationship

### Heap

Heap keeps the Derby-compatible physical page and WAL formats. RCM modernizes the implementation of the mutation underneath those formats.

This is important because the large F08 deficit is shared with upstream Derby. Fixing only Gen2 would leave the dominant shared problem intact.

### Gen2

Gen2 uses the same RawStore mutation capabilities for its CURRENT/HISTORY records and SQL PK B-tree.

Its separate ~20% `INSERT_100` tax remains a later Delos-owned problem, likely involving the wider CURRENT envelope and Gen2-specific row construction/metadata work. Do not mix that with the shared RCM qualification.

Transaction-status visibility/reclamation/cache work is already a separate completed lifecycle architecture and should not be redesigned as part of RCM.

---

## 14. Compatibility strategy

Generation 1 is deliberately conservative:

| Surface | RCM generation 1 |
|---|---|
| Heap page format | unchanged |
| B-tree page format | unchanged |
| RawStore WAL on-disk format | unchanged |
| Recovery reader | unchanged for existing insert record format |
| Logical undo / CLR | preserved |
| Group commit | existing RawStore implementation |
| SQL/JDBC/DRDA | unchanged |
| Optimizer | unchanged |
| Heap Derby physical compatibility | preserved |
| Gen2 transaction-status format | unchanged |

The new structures are runtime implementation details only.

---

## 15. Crash-state matrix

Before implementation, tests must cover at least these states.

| Crash/failure point | Required recovery behavior |
|---|---|
| before WAL reservation | no mutation visible; no durable log obligation |
| after reservation but before publication | recovery must never consume incomplete reservation; startup sees only previously published/durable frontier |
| after WAL publication but before page apply | redo may apply the logged operation; semantics equivalent to current `logAndDo` |
| during page apply | transaction/page marked failed using existing RawStore semantics; redo/undo remains authoritative |
| after page apply but before WAL durable | dirty page cannot reach disk before its `lastLogInstant` is durable |
| after WAL durable before commit | transaction remains recoverable/undoable under current transaction state |
| during commit group flush | one flush leader / existing waiter semantics; no double coordinator |
| during log-buffer/file rollover | reservation generation and checksum/file metadata must make partial next-file state non-authoritative |
| during checkpoint | FIRST/LAST/checkpoint fences preserve current transaction-table/recovery boundary |

No performance work proceeds to promotion until deterministic crash-injection tests exercise these transitions.

---

## 16. Concurrency matrix

Qualification must include:

- multiple writers inserting into the same heap page;
- multiple writers inserting monotonically increasing keys into the same B-tree right edge;
- writers targeting different leaves;
- split while other writers hold stale routing views;
- duplicate PK failure concurrent with successful inserts;
- transaction abort after prepared mutation logged/applied;
- long reader + writers;
- checkpoint during concurrent inserts;
- log buffer rollover during concurrent reservations;
- database shutdown/reopen after concurrent inserts;
- recovery from injected crash with reservations finishing out of order;
- Heap and Gen2 using the same RawStore concurrently in separate databases.

Existing isolation tests remain authority; do not weaken them to qualify RCM.

---

## 17. Implementation sequence

This sequence is intended to avoid another try-and-error campaign. Each phase exists to complete the architecture, not to hunt a benchmark win independently.

### Phase RCM-0 — executable specification and counters

No behavioral optimization.

- add equivalence tests for current `InsertOperation` runtime apply vs recovery replay;
- add test-only mutation counters sufficient to prove prepared/legacy fallback usage;
- document exact WAL/page invariants in the existing RawStore design docs;
- no benchmark decision here.

### Phase RCM-1 — prepared mutation / direct runtime apply

- introduce `PreparedRowPayload` and page-bound transient representation;
- use current durable WAL encoding;
- add direct normal-runtime page apply;
- recovery continues existing decoder;
- fallback for unsupported rows;
- preserve exclusive `BasePage` latch model.

**Do not make a KEEP/REJECT performance decision on RCM-1 alone.** It is part of the integrated architecture.

### Phase RCM-2 — validated B-tree routing view

- productionize the immutable routing-hint mechanism as a correctness-neutral accelerator;
- conditional candidate-leaf latch;
- generation/key-range validation;
- immediate fallback on contention/staleness;
- remove experiment-specific routing flags once integrated and qualified.

Again, do not tune tree depth/page-count heuristics from benchmark results.

### Phase RCM-3 — concurrent WAL reservation/copy

- split reserved/published/durable frontiers;
- reserve global order briefly;
- copy payloads concurrently;
- contiguous publication frontier;
- existing group flush;
- legacy fenced path for special records/checkpoint transitions;
- preserve current WAL bytes.

### Phase RCM-4 — integrated correctness/recovery qualification

Run the crash matrix, concurrency matrix, transaction/isolation suites, recovery/reopen tests, and repository-integrity gates.

No throughput claims before this phase is green.

### Phase RCM-5 — one canonical performance decision

Run a deliberately small, predeclared matrix:

1. BARE `INSERT_100`, 1/2/4/8 clients;
2. PK `INSERT_100`, 1/2/4/8 clients;
3. Heap, Gen2, upstream Derby, PostgreSQL, MariaDB;
4. canonical 128-B payload and normal durability;
5. dispersion rules already used by F08;
6. optional no-sync decomposition only after the durable campaign, to identify the next ceiling—not to tune RCM.

Then classify the architecture once:

`PROMOTE / MODIFY / REJECT`.

---

## 18. Promotion criteria

The exact performance outcome cannot be guaranteed before implementation, but the project needs a high bar because many 2–10% local improvements have already been rejected.

Suggested promotion requirements:

### Correctness

- all existing RawStore/Heap/B-tree/isolation tests green;
- crash matrix green;
- recovery produces byte/semantic equivalence with the legacy path;
- no weakened assertions;
- no new persistence authority.

### Quality

- repository-integrity maxima not increased;
- no permanent experiment-specific branches;
- no duplicate recovery implementation;
- no second commit coordinator;
- explicit ownership of new prepared-mutation and WAL-reservation abstractions.

### Performance

Architecture-sized improvement, evaluated primarily by scaling rather than one median:

- no 4→8 client PK collapse comparable to current Derby behavior;
- meaningful normalized 8-client PK improvement versus unchanged upstream Derby; **~1.5× or better is an appropriate target**, not a promised result;
- BARE also improves or at minimum does not materially regress;
- 1-client regression <= ~5% unless accompanied by a very large concurrency gain and explicitly accepted;
- dispersion-valid result before freezing ratios.

If integrated RCM produces only another ~5–10% class gain, reject or substantially redesign it rather than permanently carrying its complexity.

---

## 19. Non-goals

RCM generation 1 does not:

- build a second WAL;
- create a second recovery engine;
- cluster Gen2 by primary key;
- replace Derby B-tree page format;
- redesign SQL execution;
- change optimizer costing;
- solve the separate Gen2 CURRENT-row metadata tax;
- replace `BasePage` with a general MVCC buffer manager;
- introduce a new background commit coordinator;
- tune page fanout counts;
- introduce benchmark-specific shortcuts.

---

## 20. Why this design is different from the rejected experiments

The rejected experiments each changed one local authority while leaving the surrounding mutation lifetime unchanged.

RCM changes the common mutation contract coherently:

```text
legacy
------
acquire shared/exclusive infrastructure early
  -> generic record encoding/log operation
  -> globally ordered append/copy
  -> generic page decode/apply
  -> release

RCM
---
prepare immutable work privately
  -> obtain capacity / candidate privately
  -> short page validation/bind
  -> short WAL ordering reservation
  -> concurrent log copy
  -> direct prepared page apply
  -> release
  -> existing group durability/recovery
```

Its purpose is not to save one method call. It is to make the common write path **writer-local for longer and exclusively shared for less time**, while keeping RawStore's durability authority intact.

---

## 21. Source questions and staged resolution

The architecture questions are resolved at the stage that owns them rather than being turned into new benchmark experiments.

RCM-0/1 source tracing resolved the prepared-mutation questions:

1. Ordinary non-stream `StoreDataValue` fields can be snapshotted writer-locally; stream-backed and unsupported values fall back as a complete row to the inherited path.
2. The smallest boundary is a module-internal `RawStorePreparedRow` helper used by Heap and B-tree access methods; no public Derby API changes are required.
3. The transient page-native image reuses `StoredRecordHeader` and `StoredFieldHeader`, converting the exact fixed WAL field headers into the existing compressed page representation.
4. `StoredPage` can share the inherited record-header, reserve-space, overflow-page, slot-entry, and corruption-check bookkeeping while changing only how the already-prepared record bytes reach `pageData`.
5. Legacy-vs-prepared encoding is qualified by deterministic codec equivalence plus normal commit/rollback/duplicate/split/reopen and crash-recovery tests.
6. Explicit counters distinguish writer-local row preparation, conservative row fallback, transient page-image construction, direct runtime apply, legacy apply, and prepared page bytes.

The remaining questions belong to later RCM stages and are intentionally not pulled into RCM-1:

- **RCM-2:** validated B-tree routing ownership, generation/range validation, and the fate of the existing diagnostic routing snapshots.
- **RCM-3:** WAL-capacity credits, rollover/large-record handling, record classes that remain serialized, reservation failure, contiguous publication, and flush/checkpoint interaction.

These later questions must be answered by source tracing and correctness/recovery tests before their respective implementation stage; they are not reasons to restart F08 micro-benchmark experimentation.

---

## 22. Recommended immediate engineering action

Do **not** write another F08 optimization overlay.

The next implementation work should be **RCM-0 and RCM-1 together as one coherent tranche**:

1. add legacy-vs-prepared physical equivalence tests;
2. extract a prepared payload from the existing `InsertOperation` / `StoredPage.logRow` machinery rather than inventing a second serializer;
3. implement a transient direct page-apply path with unchanged durable WAL bytes;
4. qualify normal insert, rollback, duplicate PK, split/retry, recovery, and reopen;
5. only after that foundation is correct proceed to routing and WAL reservation concurrency.

No production promotion and no canonical performance judgment occurs until the integrated RCM path reaches RCM-4.

---

## 23. Architectural north star

The desired end state is:

```text
SQL / JDBC / DRDA
        |
shared relational execution
        |
   +----+-------------------+
   |                        |
Heap provider           Gen2 provider
Derby-compatible        Delos-native MVCC semantics
   |                        |
   +------------+-----------+
                |
        RawStore Concurrent Mutation
        - prepared mutations
        - short page ownership
        - concurrent WAL reservation/copy
        - existing group durability
                |
        ONE WAL / recovery / undo
```

Heap remains physically compatible. Gen2 retains its own MVCC semantics. Both stop paying unnecessary serialization and generic-mutation critical-section costs where RawStore can safely do the work privately or concurrently.

That is the level at which the F08 evidence now says DelosDB must improve.

---

## 24. RCM-0/1 implementation decision

The first qualification tranche uses two source-proven preparation boundaries while leaving all durable formats and recovery semantics unchanged.

First, Heap snapshots complete ordinary `StoreDataValue` rows **before `getPageForInsert()`**, and normal B-tree insertion snapshots its index row **before tree search**. The snapshot is writer-local and immutable: each supported field is serialized once into the exact payload bytes that the inherited `StoredPage.logColumn(...)` path would otherwise create while the target page is latched. Those bytes are then reused across page-fit retries and B-tree split/research loops.

The preparation is deliberately conservative. If any field cannot be snapshotted safely--including stream-backed values--the entire row is returned unchanged and the inherited serializer remains authoritative. Long-column/no-space decisions also fall back to the original field value so Derby's overflow semantics are preserved.

Second, `InsertOperation` still owns the exact durable optional WAL payload before `logAndDo()` enters the shared log factory. RCM-0/1 derives a **transient page-native insert image from those same durable bytes**. The normal in-process `doMe()` may apply that image directly, while a recovery-created `InsertOperation` has no transient image and therefore continues through the inherited `StoredPage.storeRecord(...)` decoder.

The resulting generation-1 path is therefore:

```text
ordinary Heap/B-tree row
        |
writer-local field snapshot before target-page ownership
        |
existing page selection / B-tree search / locking
        |
existing WAL payload, using immutable field bytes when safe
        |
transient canonical page image derived from that WAL payload
        |
normal runtime direct apply

crash recovery
        |
existing WAL bytes
        |
existing StoredPage decoder/apply path
```

This establishes an executable equivalence boundary without changing page format, WAL format, logical undo, CLR behavior, checkpoint ordering, page latching, B-tree routing, or log ordering. The capability remains qualification-only under `delosdb.experimental.rawStorePreparedInsert.enabled`.

RCM-0/1 is a correctness and ownership tranche, not a performance decision. Its focused qualification must prove codec equivalence, ordinary Heap and PK inserts, page splits/retries, duplicate-key failure, rollback, clean reopen, and crash recovery with the feature disabled on recovery. Performance is intentionally deferred until the integrated RCM architecture reaches its planned decision point.

