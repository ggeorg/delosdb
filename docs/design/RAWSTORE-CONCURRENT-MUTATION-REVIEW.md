# RawStore Concurrent Mutation (RCM): Adversarial Architecture Review

**Status:** Review of `RAWSTORE-CONCURRENT-MUTATION.md` and the RCM-0/1 implementation
**Reviewed state:** DelosDB `e636ddb7` (RCM-1 = `67a2d44d`), upstream Derby 10.17.1.0, PostgreSQL `master`, MariaDB `server-main`
**Date:** 2026-10-09
**Verdict:** **REDESIGN BEFORE IMPLEMENTATION**
**Revision 2 (2026-10-09):** incorporates team feedback on revision 1.

- **Occupancy model corrected.** Revision 1 proposed summing serialized hold times per row. That
  double-counts nested sections (a monitor held inside a latch is already part of the latch's hold
  time), and it is also the wrong aggregate for independent resources. The model is now stated as
  a bottleneck-utilization test with a non-overlapping decomposition (§F, §K, §L, §O).
- **RCM-1 conclusion narrowed.** The evidence rejects the *current* RCM-1 implementation. It does
  not prove that no prepared-mutation design could ever help.
- **Phases renamed.** The replacement sequence is now RCM-R0 … RCM-R3b.
- **Ledger specified.** The occupancy ledger now has a full specification (§O).

**Revision 3 (2026-10-10):** reviews the RCM-R0 ledger implementation, `F08-CRITICAL-PATH-OCCUPANCY.md`
and the first sampled calibration run (§P).

- **Root claim corrected.** The B-tree root is released as soon as the child latch is granted
  (`BranchControlRow.search:291-297`). It is *not* held across the leaf's WAL append. Only the
  heap insert page is held across `LogToFile`. Changed in §A, §B, §N and Top finding 1.
- **R0 gate was tautological.** `U / occupancy_per_row` equals measured throughput by construction,
  so "predicts rows/s within 15%" tested nothing. The gate is now high utilization on a stable
  instance or a verified lane, plus the predicted 4→8 and BARE/PK changes (§K, §L, §O).
- **The calibration rule could not be met.** Same-mode Heap runs differ by up to 37%, so a 5%
  whole-workload gate cannot be resolved with four runs per mode. It is replaced by a
  perturbation-invariance test (§O, §P).
- **New findings from the calibration data (§P):**
  - The ledger misses most of `LogToFile`'s real holders.
  - Heap insert-page holds include 10–21 ms stalls spent waiting for the log monitor.
  - Delos Heap runs at about 0.57× upstream Derby on the same embedded workload. This contradicts
    the Heap-beats-Derby goal and cannot come from architecture the two engines share.

**Revision 4 (2026-10-10):** direct measurement with JFR and an embedded probe (§Q). The results:

- **The 0.57× gap to Derby is mostly a sync regression.** It comes from `F_FULLFSYNC` on macOS
  during log switches, done while holding the log monitor. It is not caused by any work Delos adds
  per row. This corrects §P.2 and §P.3, which suspected commit-flush I/O and extra per-row work.
- **The scaling bottleneck for BARE is identified.** It is the single shared heap insert page, in
  both engines. About 70–74% of the CPU spent holding it goes into building the log record. The
  WAL append is 1–2%.
- **The current RCM-1 implementation has no measurable effect,** even with the sync stalls removed.
- **The plan changes:** sync decision → R1. R2 and R3b are not supported for BARE. See the
  Immediate next action.

**Revision 5 (2026-10-10):** insert lanes landed, the cross-engine source comparison, and the held-intent regrant (§R).

- **R1 is done as per-writer heap insert lanes:** BARE 2.5×, merged as `edf18924`.
- **The lock-free `pageValid` view is correct but does not raise throughput.** It is parked on a
  branch.
- **Static comparison with PostgreSQL, InnoDB, H2, Firebird and HerdDB.** Delos alone makes a
  shared lock-manager call per inserted row and runs every log record through an exclusive
  global monitor.
- **The held-intent regrant gives BARE +22%.** PK and INDEXED are now bound by B-tree page
  latching, so R2 is next.
- **The "constant log-record rate" cap is disproven** (§R.5).

**Revision 6 (2026-10-10):** R2a, insert root routing qualified and on by default (§S).

- **Inserts no longer latch the B-tree root.** PK +29%, INDEXED +13%.
- **A correctness defect in the routing snapshots is fixed.** A second BTree instance for the same
  index kept a stale snapshot. Inserts then reached leaves that do not cover their keys. Keyed
  reads on main are exposed to the same mechanism (§S.2).
- **R2b is next:** branch levels below the root and the rightmost-leaf hot spot.

**Revision 7 (2026-10-11):** R2b, the WAL append experiment and the log buffer default (§T).

- **Earlier profiles measured a debug build.** The build defaults to `delosdb.sane=true`, while
  upstream Derby releases are insane. The insert probe and every JFR profile through revision 6
  ran the sane build, so they include SanityManager work (§T.1). Throughput conclusions are
  re-measured on an insane build from here on.
- **Cached page validity merged (`6211e32e`).** It removes the allocation-cache monitor as a
  page-fetch convoy: PK +9%, INDEXED +8%, BARE +24%, with equal page counts. Two earlier
  designs were rejected because they broke Derby's page reuse (§T.2).
- **The lock-free WAL append window gives no throughput gain** (branch `wal-reserve`, not merged).
  Waiting moves from the log monitor to the B-tree leaf latch (§T.3).
- **Log buffer default raised to up to 1M (`58d6e1b1`):** INDEXED +15%, PK +8–17% (§T.4).
- **INDEXED is now bound by the leaf latch hold** (§T.5).

Labels used throughout:

- **SOURCE-PROVEN**: read directly in the cited source.
- **INFERENCE**: evidence-supported inference from source plus measured results.
- **UNPROVEN**: plausible but not yet demonstrated.
- **DISPROVEN**: contradicted by existing evidence.

Paths abbreviated: S = `delosdb-storage-derby/src/main/java/org/apache/derby/impl/store`,
E = `delosdb-engine/src/main/java/org/apache/derby/impl`.

---

## 0. Corrections to the investigation premises

Two premises in the investigation brief are wrong. They change the analysis.

1. **The F08 primary key is a 4-byte `int`, not a 16-byte key.** SOURCE-PROVEN:
   `DelosJdbcBenchmarkScenario.java:69-72` defines `id int not null primary key`.
   "16 B" is the payload width.
2. **F08 keys are not one global sequence.** SOURCE-PROVEN:
   `DelosJdbcCrossEngineConcurrency.java:8008-8011, 9784-9822`, which use
   `insertBaseId = rowCount + 1 + client * insertsPerClient`. Each client inserts its own ascending
   range, and the ranges are 250,000 apart in the canonical configuration. After the first few
   splits, each client appends to its own leaf and mostly to its own level-1 branch.
   - The **root** is the only index page every inserter shares in steady state.
   - There is **no shared hot leaf**.
   - There **is** a shared hot heap page (§B).

Two history issues affect attribution. Both were verified in git.

3. `7cf48ef9` ("Avoid non-key materialization during B-tree insert search") also **deleted the
   multi-insert-page promotion** (`switchToMultiInsertPageMode`) that `9d61c9a4` added to
   `FileContainer`.
4. `67a2d44d` ("Introduce prepared RawStore insert mutation path") **deleted the key-prefix-only
   B-tree search** that `7cf48ef9` had just added. `compareFetchDescriptor` no longer exists at HEAD.

Both deletions correspond to experiments the investigation had rejected, so they were probably
deliberate backouts. Neither commit message says so, and any result attributed to those commits
at HEAD is mislabelled.

---

## A. Executive conclusion

**RCM addresses only secondary costs as specified. The diagnosis behind it is half right.**

**The correct half.** A PK entry pays almost one more full generic mutation. SOURCE-PROVEN:
both a heap row and a leaf entry go through `insertNoOverflow` → `LoggableActions.actionInsert` →
`InsertOperation` → `Xact.logAndDo` → `FileLogger.logAndDo` → `LogToFile.appendLogRecord` →
`InsertOperation.doMe`.

**The incorrect half.** The cost does not come from serialization work, the Loggable abstraction,
or B-tree CPU. It comes from **nested serialization**: Derby holds a hot, table-wide exclusive
resource while it crosses another contended shared authority.

```text
hot exclusive heap insert page (ONE shared target per table)
     └─ held across → global LogToFile Java monitor
                      (contended park/unpark handoff, buffer-switch / log-switch I/O,
                       and commit-flush I/O by other holders: see §P)
     └─ held across → per-container allocCache monitor, lock-manager calls

exclusive B-tree root (latch-coupled; revision 3 correction)
     └─ held across → child latch acquisition (waits if the child is busy) + allocCache
     └─ released before the leaf search, leaf insert and leaf WAL append
```

PostgreSQL also holds its page lock across WAL insertion, so that alone is not the difference.
The difference is:

- **What is hot.** PostgreSQL gives each backend its own heap target block (`smgr_targblock`) and
  never holds internal B-tree pages exclusively.
- **What is nested inside the hot resource.** PostgreSQL nests a reservation that is a few
  nanoseconds of spinlock (`insertpos_lck`), and the copy runs in parallel across 8 insertion
  slots. Derby nests a contended Java monitor that sometimes performs I/O.

Against that diagnosis:

| Component | Verdict |
|---|---|
| **RCM-1** (prepared mutation) | The **current implementation** targets a cost that is not on the critical path. It moves ~100–300 ns of column encoding out of the latch and adds a re-parse plus copies under it (§G). Stop it. The prepared-mutation *concept* is not permanently disproven: it becomes relevant again only if, after the hot resources are removed, the ledger shows serialization work inside a saturated section. |
| **RCM-2** (validated routing) | The previously rejected root/branch routing experiment, productionized. Its fallback rule makes contention worse (§H). |
| **RCM-3** (concurrent WAL) | The right lever, in the wrong form and the wrong position in the sequence. The reservation must not be a Java monitor, and it only pays off after the hot exclusive resources above it are removed (§I). |

The architectural principle the evidence supports:

> **No hot exclusive resource may be held across a contended serialized section, and the
> serialized sections that remain must be lock-free and must not perform I/O.**

---

## B. Current Delos/Derby PK INSERT ownership map (Heap, default flags)

All rows SOURCE-PROVEN unless marked.

| Step | Where | Scope | Protection |
|---|---|---|---|
| Statement / row changer | `E/sql/execute/InsertResultSet.normalInsertCore` → `RowChangerImpl.insertRow` → `IndexChanger.insertAndCheckDups` | per statement / transaction | writer-local |
| Heap container open | `BaseDataFileFactory.openContainer` → `RowLocking3.lockContainer` (CIX) | **per table** | lock-table entry `ReentrantLock` (the CIS fast path does not apply to CIX) |
| Heap target page | `FileContainer.getPageForInsert:3010` → `getLastInsertedPage:3437` | **per table** | `synchronized(FileContainer)`. **A single `lastInsertedPage[0]`** shared by all writers. On NOWAIT failure it retries **the same page** with WAIT. No fanout code exists at HEAD. |
| Page validity / cache | `pageValid:2066` → `ConcurrentCache.find:284` | per container / striped | `synchronized(allocCache)`, then CHM + per-entry `ReentrantLock` |
| Heap page latch | `BasePage.setExclusive:1840` / `releaseExclusive:2009` | **per page = per table** | Java monitor + `owner` field; `notifyAll` herd on release |
| RecordId lock | `BasePage.insertNoOverflow:633` → `RowLocking3.lockRecordForWrite` (RIX) | lock manager | taken **inside** the heap latch |
| Mutation build | `LoggableActions.actionInsert:135` → `new InsertOperation` → `StoredPage.logRow:3685` | writer-local transaction log buffer | under the heap latch |
| `preDirty` | `CachedPage.preDirty:444` (+ Delos `invalidateHeapPageReadImage`, a CHM remove) | per page | under the heap latch |
| Transaction activation | `Xact.logAndDo:1221` → `TransactionTable.add:181` (first write) | **per database** | `synchronized(ttab)` |
| FileLogger | `FileLogger.logAndDo:202` | per transaction | uncontended monitor |
| **WAL append** | `LogToFile.appendLogRecord:3764` (`synchronized(this)` at :3818) | **per database** | Two `arraycopy`s. `reserveSpaceForChecksum` may call `switchLogBuffer` (32 KB CRC), which may call `flushDirtyBuffers` (**write I/O under the monitor**). The file-switch check may run `switchLogFile` (fsync + preallocation) under the monitor. **The page latch is held throughout.** |
| Page apply | `InsertOperation.doMe:169` → `StoredPage.storeRecordForInsert:7309` | per page | re-parses the logged bytes into `pageData`; still latched |
| B2I base re-open | `B2IController.init:85-135` | per table | **second heap CIX** |
| Index descent | `ControlRow.get(ROOTPAGEID)` → `BranchControlRow.search:291-299` | **root = per index** | Exclusive and **latch-coupled**: the child is latched (waiting) before the parent is released. Each level adds an `allocCache` monitor entry. The parent is released right after the child latch is granted (`:291-297`), before the recursive search, so the root is **not** held across the leaf's WAL append. It inherits leaf time only when the child latch is busy. |
| Previous-key lock | `B2IRowLocking3.lockNonScanPreviousRow:749` | lock manager (instant RIP) | under the leaf latch |
| Leaf insert | `BTreeController:977` `insertAtSlot` → **the same** `InsertOperation` → `logAndDo` → `LogToFile` → `doMe` | per leaf (client-private in F08 steady state) | leaf latch held across the global append |
| Commit | `Xact.commit:702` → `EndXact` (LAST: `doMe` runs inside the LogToFile monitor, with `synchronized(ttab)` nested) → `LogToFile.flush:3961` | per database | `logBeingFlushed` / `lastFlush` group flush; buffer seal; `rwd` write |

**Per PK insert, common case** (no split, no new page, tree height *h*):

| Item | Count |
|---|---|
| Data log records | 2, plus BeginXact/EndXact amortized over the 100-row transaction |
| Global `LogToFile` monitor entries | 2, each **made while holding a page latch** |
| Exclusive page latches | 1 + *h* |
| `allocCache` monitor entries | 1 + *h* |
| Lock-manager calls | 4 (CIX, RIX, CIX, RIP) |
| Flushes | 1 per transaction |

**Gen2 differences:**

- **Same RawStore mutation path.** SOURCE-PROVEN.
- **Fewer lock-manager calls.** `B2INoLocking` means no previous-key lock.
- **An extra per-row global monitor.** `MvccRawStoreRuntime.reserveInsertIdentifiers` is
  `synchronized` on the runtime (`:447`). SOURCE-PROVEN.
- **A commit-order publication lock.**
- **Per-row commit stamping,** unless transaction-status visibility is enabled.

---

## C. PostgreSQL ownership map

| Step | Scope | Protection |
|---|---|---|
| Tuple formation, TOAST, `index_form_tuple`, `XLogRecordAssemble` **including payload CRC** | per backend | none. **All of this happens before any buffer lock.** |
| Heap target block (`RelationGetBufferForTuple`) | per backend × relation (`smgr_targblock`) | none. **Writers do not share a target**; FSM only on a miss; extension lock only when growing. |
| Buffer lookup / pin | 128 mapping partitions / per buffer | LWLock S + atomic CAS |
| Heap page | per buffer | content lock X across `PageAddItem` + `MarkBufferDirty` + `XLogInsert` (reserve + copy), then unlock |
| B-tree internal pages (`_bt_search`) | per buffer | **BT_READ (shared), one at a time, no coupling**; Lehman-Yao right-links + `_bt_moveright` |
| Rightmost-leaf fast path (`_bt_search_insert`) | per-backend cache | **conditional** X lock. Never queues; falls back to a full descent. |
| Leaf (`_bt_check_unique`, `_bt_insertonpg`) | per buffer | X: heap S probe only for equal keys; `PageAddItem` + small `XLOG_BTREE_INSERT_LEAF` |
| WAL reserve (`ReserveXLogInsertLocation`) | global | **spinlock** around `CurrBytePos += size` |
| WAL copy (`CopyXLogRecordToWAL`) | 1 of 8 insertion slots | per-slot LWLock; copies run in parallel |
| WAL publish (`WaitXLogInsertionsToFinish`) | global | per-slot `insertingAt` + atomic `logInsertResult` |
| Flush (`XLogFlush`) | global | `WALWriteLock` acquire-or-wait group commit |

- **Per-row heavyweight locks: none.** Ownership is `xmin`.
- **Shared authorities per PK insert:** about 10 with the fast path, 14–16 with a descent.

That is **more** authorities than DelosDB. None of them is a hot exclusive resource with a sleeping
monitor or I/O nested inside it.

---

## D. MariaDB / InnoDB ownership map

Clustered PK: one tree insert, no separate heap.

| Step | Scope | Protection |
|---|---|---|
| mtr, `m_log` | per mtr | writer-local |
| `index->lock` (`btr_cur_t::search_leaf`) | per index | **S** (SX/X only on a pessimistic retry) |
| Internal pages | per page | **S, latch-coupled** |
| Leaf | per page | X until mtr commit |
| Lock-system probe (`lock_rec_insert_check_and_lock`) | partitioned | `lock_sys.latch` S + cell latch. **No lock object** when there is no conflict (implicit lock via `DB_TRX_ID`). |
| Undo (`trx_undo_report_row_operation`) | per transaction | nested mtr on the transaction's own undo page, committed **inside** the leaf window |
| Record conversion (`rec_convert_dtuple_to_rec`) | writer-local | **under** the leaf latch, after undo |
| Redo reserve (`log_t::append_prepare`) | global | `log_sys.latch` **S** + one atomic `fetch_add` |
| Redo copy | disjoint range | concurrent under S |
| Publish / write (`write_buf`) | global | the writer takes `log_sys.latch` **X**, which proves every copier has finished |
| Flush list | global | mutex only on a clean→dirty transition |

InnoDB serializes the record **inside** the leaf latch, which is the opposite of what RCM-1
assumes matters, and it still scales.

- **Structural advantage:** clustering gives one latch window per row instead of two.
- **Protocol advantages:** redo reservation is an atomic add under a shared latch, and there is
  no shared hot insert page.

---

## E. Architectural delta table

| Concern | Delos/Derby | PostgreSQL | MariaDB | Existing evidence | Severity |
|---|---|---|---|---|---|
| Base-row target selection | **one shared `lastInsertedPage` per table** | per-backend `smgr_targblock` + FSM | n/a (clustered) | heap container = 27.5 of 33.45 s latch wait (**82%**); multi-page BARE 252k → 363k | **Critical** |
| Page latch ownership | exclusive only; Java monitor + `notifyAll` | S/X content locks | S/X/SX rw-locks | not tested directly | Medium |
| B-tree internal traversal | **exclusive, latch-coupled from the root** | shared, uncoupled | shared, coupled | root ≈ 90% of index wait; bypass ≈ +10% | Medium |
| Leaf mutation | exclusive across the global monitor | X across spinlock reserve + copy | X across atomic reserve + copy | key-prefix change: no movement | Medium |
| Index tuple representation | generic `InsertOperation` + serialized `B2IUndo` | native `IndexTuple`, small redo-only record | native record + undo mtr | PK increment ≈ one heap mutation | High: the cost is one more *nested serialization*, not encoding |
| Physical mutation abstraction | generic Loggable; zero CLR cost on the forward path | resource-manager specific | mtr page ops | allocation/header fixes: no movement | Low |
| WAL reservation | **Java monitor**, fused with the copy | spinlock | atomic `fetch_add` under shared latch | flat combining: −60% contention, ≈0 throughput | **High, once heap/root are fixed** |
| WAL copying | inside the monitor (tiny) | parallel across 8 slots | parallel | concurrent-copy prototype: no cure | Low on its own |
| WAL publication | implicit (`endPosition` under the monitor) | per-slot `insertingAt` | exclusive-latch drain | n/a | Required for RCM-3 |
| Flush / group commit | `logBeingFlushed`/`lastFlush`, **plus I/O inside the append monitor** (3×32 KB buffer starvation, log-file switch) | `WALWriteLock` acquire-or-wait | `group_commit_lock` | no-sync 177k → 215k (+21%) | Medium–High |
| Allocation | `synchronized(allocCache)` on every `getUserPage` | atomic pin | buffer-fix | `pageValid` snapshot: real contention, erased in canonical runs | Medium (nested under the root) |
| Transaction / controller lifetime | 2 heap CIX + RIX per row; TransactionTable monitor nested in LogToFile for Begin/End | per-statement relation lock, no row locks | implicit locks | RecordId contention = 0 | Low |

---

## F. Reconciling every prior F08 result

**The model** (INFERENCE): at 8 clients, throughput is bounded by the **most-utilized serialized
resource instance** *R*:

```text
rows/s  ≤  U(R) / occupancy_per_row(R)        with U(R) → 1 when R is the bottleneck
```

- **`occupancy_per_row(R)`** is R's full hold time per row. It **includes** time the holder spends
  waiting for, and executing inside, authorities nested within R.
- **Nested sections are part of the outer occupancy, not added to it.** If the heap latch is held
  4.0 µs, of which 1.8 µs is inside `LogToFile`, the heap latch's occupancy is 4.0 µs, not 5.8 µs.
- **Independent resources are not summed either.** For example, the heap latch and the root latch
  are acquired one after another, not nested. A single writer's *latency* adds them, but aggregate
  *throughput* is bounded by the larger of the two occupancies, divided by its parallelism (number
  of instances).
- **Why nesting still matters.** The outer resource's occupancy grows by the nested resource's
  *queueing* delay. That delay increases with client count, and it is what turns a short global
  section into a long per-table one.

Revision 1 wrote this as `1 / Σ(serialized critical paths)`. That double-counted nested sections
and has been corrected. The observations below are unchanged under the corrected formulation.

| Observation | Explanation under the model |
|---|---|
| BARE ≈ 4.79 µs/op, PK ≈ 8.20 µs/op; PostgreSQL increment only 0.22 µs | BARE has one hot latch held across one global-monitor entry. PK adds a second log-monitor occupant per row. The heap-latch holder now queues behind other clients' index appends, so the heap critical path grows by roughly one more monitor occupancy (~3.4 µs). This is why heap latch wait dominates **even in PK runs** (82%), and why the PK increment ≈ one heap mutation regardless of B-tree logic. PostgreSQL's nested section is a spinlock of a few nanoseconds. |
| Multi-page helped BARE but not PK | Splitting the heap target removes the hot heap latch. For BARE, only the short log monitor is left, so it scales. For PK, the root latch is still exclusive and coupled, with `allocCache` and the child `getPage` nested inside it, and log-monitor traffic is doubled. The convoy moves to the root and the monitor (195k → 161k). |
| Root/branch routing ≈ +10% | With hot heap pages still present, the root is not the critical path. Coupling makes the root *look* like 90% of index wait: whoever holds the root waits for its child, so waits below it are re-counted at the root. |
| WAL flat combining: −60% visible contention, ≈0 throughput | The log monitor is nested *inside* the heap latch. A cheaper monitor shortened the heap holder's wait, but the heap latch convoy still set the rate. The waits moved; they didn't disappear. |
| Concurrent WAL copy failed | Same nesting. In addition, the prototype took `synchronized(this)` **twice** per record: reserve, then complete + `notifyAll` (`029eb4d6`). It doubled the expensive part (contended monitor handoff) to parallelize the cheap part (a few-hundred-byte `arraycopy`). |
| No-sync helps (+21%) but leaves a gap | Sync I/O reaches the critical path only through the append monitor (buffer starvation or file switch while page latches are held) and through commit. Removing it shortens one nested section; the topology is unchanged. |
| Combined stack: 117k → 237k | It is the only experiment that removed **both** hot exclusive resources (heap target, root) **and** a nested authority (`pageValid`/`allocCache`). The model predicts exactly this super-additive result. Residual 237k ≈ 4.2 µs for 2 mutations ≈ 2.1 µs per log-monitor entry. That is a plausible contended-Java-monitor handoff cost (UNPROVEN), it is the remaining ~2× to PostgreSQL, and it is exactly what a lock-free RCM-3 would remove. |
| Allocation / key-prefix / header-cache fixes moved nothing | They shorten *uncontended* writer-local work. Throughput is set by occupancy of serialized sections. |
| Canonical fanout +4.6% vs diagnostic multi-page (large) | **Not explained by source alone.** The productionized version is contention-triggered and draws from a small shared pool, so collisions and the WAIT fallback probably persist (UNPROVEN). The occupancy ledger (RCM-R0, §O) should explain it. |

The model accounts for every result. The diagnosis "generic machinery is expensive" does not
explain why flat combining did nothing, or why only the combined stack worked.

---

## G. RCM-1 adversarial review

| # | Finding | Severity | Label |
|---|---|---|---|
| G1 | The transient page image is built in the `InsertOperation` constructor (`InsertOperation:111-113`) **under the page latch**: full WAL-payload parse, re-encode into a new stream, `Arrays.copyOf`, two `StoredRecordHeader` allocations (`PreparedInsertRecord:43-85`). This is the same parse legacy `doMe` performed, moved earlier and with extra copies. It does not shorten the latched interval. | **HIGH** | SOURCE-PROVEN |
| G2 | The WAL append (global monitor, possible I/O) stays inside the page latch. At 8 clients it dominates the latched interval, not serialization. | **HIGH** (wrong cost targeted) | SOURCE-PROVEN mechanism / INFERENCE magnitude |
| G3 | Row-byte copies rise from 3 to 7, with ~4 new allocations per field and ~5 per row. B-tree prepares *before* the duplicate check (`BTreeController:719` vs `:852`), so duplicate failures pay for preparation. | MEDIUM | SOURCE-PROVEN |
| G4 | When the flag is on, the image is built for **every** `InsertOperation`, including split control rows, overflow/long-column pieces and fallback rows. None of them get a stage-1 benefit. | MEDIUM | SOURCE-PROVEN |
| G5 | `PreparedStoreField` keeps a reference to the caller's value for fallback (`PreparedStoreField:15`). This contradicts the design's own rule "never retain mutable caller-owned aliases" (§8.3). It is safe today only because nothing mutates the row within one call. | MEDIUM | SOURCE-PROVEN / INFERENCE |
| G6 | `SQLBinary`/`SQLBlob` with `_blobValue` and `SQLChar`/`SQLClob` with `_clobValue` pass the `returnStream() == null` gate. They are fully materialized at prepare time, then re-read on long-column fallback, which assumes the user LOB is re-readable. There is no size cap on large non-stream values, which are serialized twice. | MEDIUM | SOURCE-PROVEN |
| G7 | A prepare-time `IOException` wraps without unwrapping the nested `StandardException` (`RawStorePreparedRow:73-75`), so a UDT error can surface with a different SQLState. | LOW | SOURCE-PROVEN |
| G8 | **No safety blockers on ordinary rows.** Snapshots are per call; the bytes are page-independent; page-specific binding (`slotFieldSize`, space checks, long-column threshold, record header, reserved space) happens per target page; recovery-created operations have `preparedInsert == null` and use the legacy decoder; undo, CLR and purge never use `doMe`; encryption is consistent. | — | SOURCE-PROVEN |
| G9 | **Test gaps.** No comparison of `logColumn(PreparedStoreField)` and legacy `logColumn` WAL bytes. No `pageData`/slot/`freeSpace`/`firstFreeByte` equality between direct and legacy apply, or between runtime and recovery images. No concurrency. No coverage of nulls, long rows, LOBs, UDTs, multi-column or non-unique indexes, reserved space, encryption or Gen2. No redo-then-undo recovery of uncommitted prepared inserts, savepoint rollback or crash mid-split. Splits and retries are never asserted to have occurred. No default-off assertion. | **HIGH** (the "executable equivalence" claim is not yet executed) | SOURCE-PROVEN |
| G10 | `Boolean.getBoolean` is evaluated per row and per `doMe`, including during recovery, even when the feature is off (`RawStorePreparedInsertAccess:32-34, 88-90`). Counters are global static `LongAdder`s, so parallel tests can interfere. | LOW | SOURCE-PROVEN |

---

## H. RCM-2 adversarial review

- **It is the old experiment, productionized.** SOURCE-PROVEN.
  - The machinery already exists: `BTree.RootRoutingSnapshot`, `BTreeBranchRoutingSnapshots`,
    `BTreeInsertRoutingSnapshots`, behind default-off flags (`BTreeController:72-77`; commits
    `6797e724`, `04791fda`, `3fef81c2`).
  - RCM-2 adds a NOWAIT leaf latch, renames snapshot identity as "generation", and removes the
    flags.
  - Ownership is unchanged by design (§9.1), so its ceiling is the ~10% already measured.
- **Its fallback rule makes contention worse.** INFERENCE (strong). RCM-2 falls back to the
  canonical path when the leaf is busy. That path latches the **root** exclusively and then waits on
  the same busy leaf *while holding the root*, turning a local leaf wait into a tree-wide root
  convoy. The correct rule is the reverse:
  1. When the route is valid, **wait** on the leaf while holding nothing. This cannot deadlock.
  2. Then validate.
  3. Fall back only when the snapshot is stale.
- **Routing cost vs ownership.** Routing CPU (root binary search) is already bypassed by the
  unconditional root snapshot (`BTree.java:225`). What remains is the root's **exclusive ownership
  plus latch coupling**. Only removing root and branch latches from the common path changes that.
- **"Generation + key-range" validation needs a high key Derby does not have.** For an append, the
  leaf alone cannot prove the key belongs there. The proof must come from the parent snapshot still
  being current. That is sound, because a leaf split updates the parent while the leaf is still
  latched (`LeafControlRow:603-700`).

**The smallest safe route to shared internal traversal** is an optimistic, latch-free descent, not a
BasePage read latch:

1. Keep immutable routing snapshots for **every** internal level, lifting the `rootLevel == 2`
   restriction. They are invalidated by the existing `pageAboutToChange` hook (called from
   `StoredPage.logAction`) and on eviction (`auxObjectInvalidated`).
2. Latch only the leaf, with a waiting `setExclusive` while holding nothing, and check that the
   parent snapshot is still current. On failure, release and retry. After a bounded number of
   retries, use the canonical coupled descent.
3. Splits, `B2IUndo.findUndo` and recovery stay on the canonical exclusive coupled path.

This needs no Lehman-Yao high keys and no format change. A real shared mode on `BasePage` should
still be avoided, for three reasons:

- `headers[]` is filled on demand during reads.
- `ControlRow` is built lazily as mutable page-auxiliary state.
- Observer and `nestedLatch` semantics assume a single owner.

**Remaining risks:**

- Snapshot churn while the tree is shallow: every leaf split rewrites the root.
- Memory for per-level snapshots.
- Every split still takes the root exclusively, roughly once per ~200 inserts per client
  (INFERENCE).

---

## I. RCM-3 adversarial review: crash, recovery and concurrency

### Source facts the design must respect (SOURCE-PROVEN)

- **Frame.** `int len | long instant | payload | int len`, 16 bytes of overhead
  (`LogAccessFile:363-392`). The instant is the record's own start offset,
  `(fileNumber << 32) | position` (`LogCounter`). The final size is known before append:
  `FileLogger.logAndDo` serializes, and encrypts if applicable, first.
- **Records never span log files.** `appendLogRecord` switches files before reserving.
- **Checksums are per buffer, not per record.** A `ChecksumOperation` at the start of each 32 KB
  buffer covers the whole buffer. Its CRC is computed when the buffer is sealed
  (`switchLogBuffer:581-630`).
- **Buffers are not zeroed on reuse** (`LogAccessFileBuffer:66-71`).
- **Recovery `Scan` stops at the first anomaly**: a length mismatch, `instant < knownGoodLogEnd`, or
  a checksum failure. It then zero-fills the tail (`LogToFile:1116-1150`).
- **`flush()` assumes every byte below `endPosition` is already in a buffer**, and its leader does
  not re-check `wherePosition < lastFlush`.

### State machine

Invariants:

- **P1:** `preDirty` happens before reservation.
- **P2:** the page LSN equals the record start instant, assigned in `doMe` after publication.

| State | Entered when | Crash in this state |
|---|---|---|
| **reserved** | position assigned; exact size; buffer capacity secured beforehand | Safe. The bytes are unsealed and never on disk, and no page carries this LSN. |
| **populated** | header (with instant) and payload copied into the disjoint range | Same as reserved. An unsealed buffer is never written. |
| **published** | the contiguous frontier advanced through this record | If later sealed and written, redo applies it normally. If lost, any page carrying this LSN was also never written (WAL-before-data). |
| **durable** | `lastFlush ≥ record end` | current semantics |

### Hard rules RCM-3 must add

1. **Seal only fully populated buffers.** Never seal or write a 32 KB buffer, or switch log buffer
   or file, while `published < reserved` within it. Otherwise the CRC covers a hole of stale bytes.
   Recovery would then truncate at the checksum and **silently discard later committed records**,
   whose pages may already be on disk. **BLOCKER if omitted.**
2. **Flush must wait for publication.** `flush(B)` waits until `publishedEnd ≥ B.end` before
   sealing, and the leader loops until `lastFlush > where`. Re-pointing `potentialLastFlush` to
   `publishedEnd` without that loop lets the leader return early and breaks WAL-before-data.
   **BLOCKER.**
3. **Reservations must be infallible once taken.** The WAL format has no variable-length filler
   record, and adding one is a format change upstream Derby cannot read. Materialize the payload
   first and make the copy a pure memcpy. On any failure, call `markCorrupt` (as `logAndDo` already
   does when `doMe` fails, `FileLogger:381-392`) and wake waiters.
4. **Never hold a page latch while waiting for capacity.** If writer A holds a latch while copying
   and a flusher waits on A's publication, that is deadlock-free only if the copy cannot block.
   Secure capacity before latching; otherwise take the legacy path.
5. **Drain before every other global log operation.** Each of these must wait for
   `published == reserved` before it runs:
   - the file switch, including the 1 MB-interval switches started from `flush`/checkpoint;
   - `freezePersistentStore`;
   - `endLogBackup`, which reads `endPosition` unsynchronized;
   - archive.

   `redoLWM = currentInstant()` remains safe only because of P1.
6. **The reservation must not be a Java monitor.** Use a CAS or `fetch_add` on a packed
   (buffer, offset) word, with a short exclusive path for rollover, as InnoDB does. Publish through
   per-slot progress that the flusher scans, as PostgreSQL does. **Never `notifyAll` per record.**
   This is what the rejected prototype got wrong.
7. **FIRST/LAST records keep the legacy fence.** `EndXact.doMe` nests the `TransactionTable`
   monitor.

### Separate and cheaper

Today the append monitor can perform I/O while page latches are held, in two ways:

- **`flushDirtyBuffers`** when all 3 × 32 KB buffers are in flight (`LogAccessFile:496-561, 604-611`);
- **`switchLogFile`**, which writes the end marker, then fsyncs, preallocates and syncs again
  (`LogToFile:2011-2120`).

Moving that I/O off the append path needs no format change, and it probably belongs before RCM-3.
Its magnitude is UNPROVEN.

---

## J. Alternative architecture

The alternative has three parts, which replace RCM-1 and RCM-2 and reshape RCM-3:

| | **J1. Writer-affine heap targets** | **J2. Latch-free B-tree descent** | **J3. Log append pipeline** |
|---|---|---|---|
| Boundary | `FileContainer` insert-target selection | `BTreeController.findInsertLeaf` / `BranchControlRow.search` | `LogToFile.appendLogRecord` / `LogAccessFile` |
| Change | Per-transaction or per-connection last-insert page with no shared target; a shared candidate list only on a miss (PostgreSQL `smgr_targblock` semantics) | Per-level immutable snapshots; latch only the leaf, waiting while holding nothing; canonical path for splits, undo and recovery | (a) Buffer switch, `flushDirtyBuffers` and file-switch preallocation off the append path; more buffers. (b) Lock-free reservation + concurrent copy + publication rules from §I. |
| Unchanged | page format, `addPage`, allocation records, recovery, locking | page format, split protocol, logical undo | WAL bytes, recovery `Scan`, group flush, checkpoint |
| Expected benefit | Removes the dominant 82% of measured latch wait. The stack showed this is necessary. | Removes root coupling and nested `allocCache` (~10% alone, but it unblocks J3). | Removes the residual per-mutation monitor handoff, plausibly most of the remaining ~2×. |
| Correctness risk | Low: same page operations, different page choice | Medium: completeness of snapshot invalidation | (a) Low, (b) High |
| Migration complexity | Low | Medium | (a) Low, (b) High |
| Heap / Gen2 / Derby compatibility | ✔ / ✔ / ✔ | ✔ / ✔ / ✔ | ✔ / ✔ / ✔ (formats unchanged) |

J1 must be tested explicitly against the canonical +4.6% fanout result. It is only materially
different if **no target is shared** in steady state.

---

## K. Recommended implementation sequence

| Step | Objective | Source boundary | Correctness gate | Recovery gate | Concurrency gate | Promote / reject |
|---|---|---|---|---|---|---|
| **RCM-R0** | **Critical-path occupancy ledger** (diagnostic, default off; spec in §O) | latch/monitor acquire and release sites in §O | no behaviour change; flag read once into a `static final`; flag-off path byte-identical | n/a | non-overlapping decomposition: outer hold = self + nested wait + nested hold; every monitor satisfies the holder-completeness check (§O) | **Confirm the model** only if all three hold: (1) at 8 clients a stable instance (root, `LogToFile`) or a *verified* heap target lane reaches U ≥ ~0.7; (2) the 4→8 growth of its hold sits in the nested wait components; (3) the BARE→PK difference matches the extra nested occupancy. **Reject it** if no instance or lane exceeds U ≈ 0.6 at 8 clients: the bound is then elsewhere (CPU, DRDA/client, GC). Do not use `U / occupancy_per_row` as a prediction: it equals measured throughput by construction. |
| **RCM-R1** | J1 writer-affine heap targets | `FileContainer.getPageForInsert`, `getLastInsertedPage` | heap/RawStore suites; page-full, page reuse, `GET_PAGE_UNFILLED` | crash during concurrent `addPage` | counters prove no shared target in steady state with 8 writers | Promote if heap-latch share of wait falls below 20% at 8 clients (BARE and PK). |
| **RCM-R2** | J2 optimistic/latch-free descent (replaces RCM-2) | `BTreeController.findInsertLeaf`, `BranchControlRow.search`, snapshot classes | split during stale snapshots; duplicate-key races; logical undo after concurrent splits | crash mid-split | ledger shows ≈0 root hold per insert off the split path | Promote if R1+R2 (no WAL change) reach at least the diagnostic stack (~237k, ~1.5× Derby at 8-client PK). |
| **RCM-R3a** | J3a: no I/O inside the append monitor | `LogAccessFile.switchLogBuffer`/`flushDirtyBuffers`, `LogToFile.switchLogFile` | log switch / checkpoint / backup suites | crash at each switch point | ledger shows ≈0 I/O share of monitor hold time | Promote on that ledger result plus suites green. |
| **RCM-R3b** | J3b: RCM-3 redesigned per §I (lock-free reservation/publication) | `LogToFile.appendLogRecord`, `flush`, `LogAccessFile` | byte-identical WAL vs legacy for the same operation order | full doc §15 crash matrix plus out-of-order completion with `kill -9` and a sealed-buffer audit | freeze/backup/switch drains; no page latch held while waiting for capacity | Promote on doc §18 criteria: ≥1.5× Derby at 8-client PK, no 4→8 collapse. |
| — | Current RCM-1 / RCM-2 disposition | `InsertOperation`, `PreparedInsertRecord`, `RawStorePreparedRow`; routing snapshot classes | — | — | — | **RCM-1:** keep default off, stop development, remove later unless R0+ evidence justifies it; benchmarking it only to prove it ineffective is not worth the cost. **RCM-2:** cancel the current design; R2 replaces it. Revisit prepared payloads only if the ledger shows serialization inside a saturated section after R1–R3b. |

The order matters. R1 and R2 remove the hot exclusive resources. R3a and R3b then shorten the
global section that becomes the bottleneck once those are gone. Running R3b first repeats the
failed concurrent-copy experiment.

**Gen2 work, in parallel and independent of the above:**

- Remove the per-row `synchronized reserveInsertIdentifiers`.
- Make the canonical server benchmark run the promoted Gen2 configuration (see §M).

---

## L. Kill criteria

Stop the relevant work, or the whole program, if any of the following holds:

1. **The model doesn't hold.** At 8 clients, with a holder-complete ledger, no serialized resource
   instance or verified heap target lane exceeds utilization ≈ 0.6. Or the saturated resource's
   4→8 growth is in its self time rather than in nested waits. The bound is then not nested
   ownership, and every RCM-R step is the wrong direction.
2. **J1 + J2 fall short.** Together they do not bring 8-client PK heap to at least 1.3× Derby,
   **and** the ledger does not show the log monitor as the new dominant section. Do not build J3b.
3. **The log monitor isn't the bottleneck.** After J1 + J2, log-monitor occupancy per record
   (hold + handoff) is under ~0.5 µs. J3b has no headroom; stop.
4. **J3b needs a format change.** Any viable J3b design requires a WAL filler record or another
   format change to make reservations fallible. Heap–Derby compatibility rules that out.
5. **The integrated gain is too small.** R1–R3b together yield under 1.3× at 8-client PK versus
   Derby. Accept that a ~2× residual is architectural (two latch windows per row) and move the
   investment to Gen2-specific layout.
6. **The coordination cost is too high.** The drain/fence rules for switch, freeze, backup and
   checkpoint need more than about three new coordination states in `LogToFile`. Complexity then
   exceeds the plausible benefit.

---

## M. Additional suggestions

These came up during the review and are independent of the RCM decision.

1. **Fix the canonical server-container benchmark configuration.** The 2026-10-09
   `runDelosJdbcServerContainerComparison` run reported `MVCC Gen2-A1 server enabled: false` and
   `transaction-status visibility server enabled: false`. Its MVCC numbers therefore measure the
   legacy Gen2 path, not the promoted architecture. The run also contained **no INSERT workload**:
   the default matrix is PK reads and indexed updates only.
2. **The server-container read cells do not discriminate between engines.** Every engine,
   including PostgreSQL and MariaDB, converged on ~7.7k tx/s at 8 clients. The limit is client
   round-trips through the Docker Desktop VM network, not the databases. Use embedded or host-mode
   runs, or more in-flight work per client, for read comparisons.
3. **Today's results against the stated goals** (8 clients, tx/s):

   | Workload | ops/tx | Heap | Derby | Heap vs Derby | MVCC | PostgreSQL | MariaDB | MVCC vs PG / MariaDB |
   |---|---|---|---|---|---|---|---|---|
   | Disjoint indexed update | 1 | 8,556 | 8,343 | +3% | 4,438 | 7,621 | 7,076 | 58% / 63% |
   | Disjoint indexed update | 10 | 1,525 | 1,542 | −1% | 1,152 | 1,531 | 1,508 | 75% / 76% |
   | Contended indexed update | 1 | 2,871 | 2,757 | +4% | 1,954 | 2,910 | 3,978 | 67% / 49% |
   | Contended indexed update | 10 | 447 | 470 | −5% | 395 | 347 | 508 | 114% / 78% |

   At 1 client, disjoint update with 10 ops/tx, Heap is 13% slower than Derby (435 vs 501). That cell
   is worth a look. Re-run MVCC with the promoted Gen2 flags before drawing conclusions about it.
4. **Commit hygiene.** When a commit backs out a rejected experiment, say so in the message, and put
   the backout in its own commit (see §0, items 3–4).
5. **Gen2 global monitors.** `reserveInsertIdentifiers` and `reserveTransactionId` are `synchronized`
   per database. They become critical sections as soon as the shared heap/root hot spots are
   removed, so fix them before measuring Gen2 against PostgreSQL on inserts.

---

## N. Team assumptions

| # | Assumption | Verdict | Why |
|---|---|---|---|
| 1 | The F08 problem is distributed physical-mutation ownership, not one hot lock | **PARTIALLY SUPPORTED** | Not one lock, but not "the machinery" either. The shared heap target is held across the log monitor. The coupled root nests only child-latch acquisition and `allocCache` (revision 3). The super-additive stack result is the evidence for nesting. |
| 2 | The incremental PK cost proves the generic B-tree path is disproportionately expensive | **NOT SUPPORTED** | The entry costs about one more *serialized occupancy*. B-tree-specific CPU reductions (key-prefix search, routing CPU) did not move throughput. |
| 3 | Moving serialization outside exclusive page ownership will materially help | **NOT SUPPORTED** (current evidence) | The current implementation moves ~100–300 ns out and adds a re-parse plus copies under the latch (§G). This is not a permanent disproof: it could matter later if serialization shows up inside a saturated section. |
| 4 | BasePage's exclusive latch model is a major scalability limitation | **PARTIALLY SUPPORTED** | Leaf and heap exclusivity match PostgreSQL. What hurts is *which* pages are hot and what is nested inside them. Exclusivity matters only at the coupled root. |
| 5 | PostgreSQL's BT_READ internal traversal is materially relevant | **PARTIALLY SUPPORTED** | Only via the root, which is bounded at ~10% today. It becomes more relevant after J1. |
| 6 | Loggable/Compensation adds meaningful fixed per-mutation overhead | **NOT SUPPORTED** | Zero forward-path CLR cost; allocation reductions moved nothing. |
| 7 | Concurrent WAL reservation/copy can be added without replacing recovery | **SUPPORTED, with conditions** | Position-addressed frames, no file spanning and a stop-at-anomaly scan allow it, but only with §I rules 1–5 and infallible reservations. |
| 8 | Heap and Gen2 can share the same modernized RawStore mutation API | **SUPPORTED** | Gen2 uses the same `insertAtSlot` → `InsertOperation` → `LogToFile` path. It also has extra global monitors of its own. |
| 9 | Modernize RawStore rather than build a Gen2-native engine | **SUPPORTED** | The dominant costs are in container and log layers shared by both. A native engine would need its own WAL, which is forbidden. |
| 10 | There is enough evidence to stop lock-by-lock experiments | **PARTIALLY SUPPORTED** | Stop single-lock *optimizations*. But prior attribution measured *wait*, never *hold time including nested waits*, and that is the quantity that explains the scaling curve. |

---

## Final verdict

**REDESIGN BEFORE IMPLEMENTATION**

## Top 5 findings

1. **The F08 deficit comes from nested serialization.** The shared per-table heap insert page is
   held across the global `LogToFile` Java monitor (and `allocCache`). The latch-coupled root nests
   child-latch acquisition and `allocCache`, but not the WAL append. This is the only model that
   explains why flat combining did nothing while the combined stack gave about 2×. The heap page
   latch is 82% of all latch wait even in PK runs. The first ledger data supports the heap half:
   heap-page holds include 10–21 ms stalls waiting for the log monitor (§P).
   *Revision 4 qualifies this finding.* Those stalls came from macOS `F_FULLFSYNC` during log
   switches (§Q.2). With them removed, the shared heap page is still the bottleneck. But its hold
   is dominated by building the log record inside the latch, not by nested waits (§Q.3).
2. **The current RCM-1 implementation does not shorten the critical section and adds work under
   it.** The transient page image moves the WAL re-parse earlier within the same latch and adds
   four copies. The safety core is sound, but the equivalence tests the design relies on do not
   exist yet. Stop it. Prepared mutation as a concept stays open, pending occupancy evidence.
3. **RCM-2 is the rejected routing experiment, and its fallback rule is backwards.** Replace it
   with a latch-free optimistic descent that waits on the leaf while holding nothing.
4. **RCM-3 is the right lever, but it needs lock-free reservation, drain-before-seal and infallible
   reservations.** Without drain-before-seal, the per-buffer checksum turns an unpublished hole into
   silent truncation of committed records.
5. **The investigation's premises and history need correcting.** The F08 key is an `int` in
   per-client ascending ranges, so there is no shared hot leaf. Two recent commits silently backed
   out earlier work. The canonical server benchmark ran Gen2 with A1 and transaction-status
   visibility disabled.

## Immediate next action

*Revision 5:* the current plan is §R.6. R1 (lanes) and the held-intent regrant are done. Next is
R2, qualifying the root routing snapshot and moving to shared or optimistic descent, because
PK and INDEXED inserts are now bound by B-tree page latches. The sync decision below is still
open. The revision 4 list follows for the record.

*Revision 4* replaced the revision 3 list. §Q gives the evidence.

1. **Decide the sync semantics, then re-baseline.** Commit `6e35fbea` changed
   `DirRandomAccessFile.sync()` from `fsync` to `FileChannel.force(true)`. On macOS that is
   `F_FULLFSYNC`, which costs about 4.3 ms instead of 0.03 ms. Log switches run inside
   `LogToFile.flush()` under the log monitor, and each does two of these syncs. Together they
   account for most of the 0.57× gap to Derby on the laptop. Either restore `fsync` for log switch
   and checkpoint, or keep the full flush and move preallocation and switching out of the monitor
   (R3a). Every macOS baseline taken before this decision is skewed.
2. **Run the same JFR probe on the PK shape** (§Q.5). The BARE result is established. PK still
   needs to show whether the root or the leaf adds a second hot resource.
3. **Then R1, writer-affine heap insert targets.** With the sync stalls removed, the shared heap
   insert page is the limiting resource, and serializing the row into its log record dominates its
   hold time (§Q.3).
4. **Ledger work (§P.4) is now optional.** Its job, finding which resource saturates and what holds
   it, is done for BARE by JFR. Keep it only for quantitative tracking of R1, not as a gate.

---

## O. RCM-R0 occupancy ledger specification

### Principles

- **Off by default, with no behavioural change.** Read the flag once into a `static final`. Do not
  use per-call `Boolean.getBoolean`, which is the RCM-1 pattern noted in G10. With the flag off, the
  hot path must be byte-identical.
- **Accumulators only.** Use `LongAdder` or striped primitive counters. No event stream and no
  allocation in the hot path.
- **Attribute to instances, not classes.** Record each hot *page instance* separately (heap target
  page, root page), not only per container. Utilization is a per-instance quantity.
- **Measure non-overlapping time.** Each outer section records:

  ```text
  outer hold  =  self time  +  nested wait  +  nested hold
  ```

  The nested components come from a thread-local "currently held outer resource" marker that nested
  sections charge into. Report the identity check (the components sum to the hold within ~5%) as a
  validity condition of the run.
- **Check holder completeness (revision 3).** For every Java monitor with N contending threads,
  total wait cannot exceed roughly `(N − 1) × total hold` unless some holders are missing from the
  ledger, or handoff latency dominates. Every `synchronized (this)` on `LogToFile` must be
  instrumented, including `flush()`, its `finally` block, the checkpoint and switch blocks, and the
  `synchronized` getters. The ledger must also record the handoff gap: the time from one holder's
  release to the next holder's acquisition.
- **Calibrate by perturbation invariance, not by whole-workload throughput (revision 3).** Each
  timed section adds `System.nanoTime()` calls, which inflate the intervals being measured. Do not
  gate on "ledger-on within 5% of ledger-off" unless repeated off runs are themselves stable to
  well under 5%. The first calibration's off runs were not (§P). Instead:
  - Run the same cell at two sampling rates (for example 1 in 32 and 1 in 512). The ledger is valid
    if the estimated U and the per-row partition of the top resources agree within their
    between-run dispersion.
  - Record the clock reads per sampled interval, so that per-interval inflation can be bounded.
  - Report the on/off throughput ratio with a confidence interval, not as a pass/fail gate.

### Resources and fields

| Resource | Counts | Times (ns) | Nested decomposition |
|---|---|---|---|
| Heap insert page (per page instance) | acquisitions, NOWAIT failures, WAIT retries | wait, hold | self; record lock (RIX); `preDirty`; `LogToFile` wait; `LogToFile` hold; `doMe` |
| B-tree root (per index) | acquisitions (insert, split, undo separately) | wait, hold | self; child-latch wait; `allocCache` wait/hold; page-cache find |
| B-tree leaf (aggregate) | acquisitions | wait, hold | self; previous-key lock; `LogToFile` wait; `LogToFile` hold |
| `allocCache` (per container) | entries, by caller (heap / index level) | wait, hold | — |
| `LogToFile` monitor (global) | acquisitions, by record class (data, BeginXact, EndXact, flush) | wait, hold | ordinary append; `switchLogBuffer`; `flushDirtyBuffers`; `switchLogFile`; nested `TransactionTable` for FIRST/LAST |
| Commit flush | flushes, leader vs follower | wait, sync | — |

### Derived outputs, per run

Each output is computed per operation, per transaction and per client, separately for BARE and PK
at 4 and 8 clients:

- **Utilization** `U = Σ hold / wall-clock` for each resource instance.
- **Per-row occupancy** for each instance, with its non-overlapping decomposition.
- **Capacity bound** `1 / occupancy_per_row` for the most-utilized instance. Note that
  `U / occupancy_per_row` equals measured throughput by definition, so it is not a validation
  (revision 3).
- **Heap target lane** per container: the time during which at least one last-insert-candidate
  page is held, and the time during which two or more are. Without this, per-page rows cannot be
  combined into a lane utilization (§P).
- **Between 4 and 8 clients:** which nested component of the saturated resource grew. This
  identifies queueing-driven inflation, the defining signature of nested serialization.
- **The canonical-fanout question from §F:** compare target-page collisions and WAIT retries
  between the canonical and diagnostic multi-page configurations, if those can still be built.

### What the result decides

| Ledger outcome at 8 clients | Next step |
|---|---|
| Heap page saturated; its growth is mostly nested `LogToFile` wait | R1, then re-measure |
| Root saturated (after R1); growth is mostly child-latch / `allocCache` wait | R2 |
| `LogToFile` saturated; hold dominated by `flushDirtyBuffers` / `switchLogFile` | R3a |
| `LogToFile` saturated; hold dominated by ordinary append and handoff | R3b |
| Nothing saturated (U < ~0.6 everywhere) | Stop. Kill criterion 1. |

---

## P. Review of the RCM-R0 implementation and first calibration data (revision 3)

Reviewed: uncommitted working tree on `e636ddb7`. That covers `RawStoreOccupancy.java`, the
instrumented `BasePage`, `FileContainer`, `ControlRow`, `HeapController`, `FileLogger`,
`LogAccessFile` and `LogToFile`, the campaign harness, `F08-CRITICAL-PATH-OCCUPANCY.md`, and the
evidence under `build/reports/delosdb/benchmarks/f08-occupancy-sampled-calibration/`.

### P.1 Where the team's document is right and this review was wrong

| Point | Verdict |
|---|---|
| Root not held across the leaf WAL append | **Correct, SOURCE-PROVEN** (`BranchControlRow.search:291-297`). Revision 3 fixes §A, §B, §N and Top finding 1. |
| `U / occupancy_per_row` agreeing with throughput is not validation | **Correct.** It is an algebraic identity. The §K gate has been rewritten. |
| A per-page span or a single acquisition can look fully utilized | **Correct.** The evidence below confirms it: per-page "span utilization" reaches 0.98 on pages with 2–7 sampled acquisitions. |
| Stop wider measurement after failed calibration | **Right to stop, wrong diagnosis.** See P.2. |

### P.2 The calibration failure says more about Delos Heap than about the ledger

Calibration run, BARE INSERT_100, 4 clients, 1 M rows (rows/s):

| | OFF runs | ON runs | Spread within mode |
|---|---|---|---|
| Delos Heap | 193k, 202k, 198k, **127k** | 203k, 194k, 157k, 174k | **up to 37%** |
| Upstream Derby (never instrumented) | 329k, 341k, 326k, 346k | 357k, 341k, 347k, 337k | ~±5% |

- **The 5% gate cannot be resolved here.** Same-mode Heap runs differ by up to 37%, so four runs
  per mode cannot distinguish a 5% overhead from noise. The –5.7% pooled ratio is *inconclusive*,
  not a failure. That is why §O now uses a perturbation-invariance test.
- **The instability belongs to Heap, not to the host.** The Derby reference runs in the same time
  windows and stays within about ±5%. A saturated lane that absorbs log-monitor stalls (P.3) would
  produce exactly this pattern. INFERENCE.
- **Heap is at about 0.57× Derby in all eight runs.** This is the most important number in the
  evidence. Both engines nominally share the heap-target, root and `LogToFile` architecture, so
  nested serialization *as a design* cannot explain this gap. Delos adds at least ~1.4 µs per row
  of serialized work, or the two workers are configured differently. This violates the
  Heap-beats-Derby goal, independently of RCM. The first check is configuration parity: page cache,
  log buffer, write-sync mode, durability and JVM flags, compared between the two worker JVMs.

### P.3 What the sampled ledger already shows (estimates ×32; INFERENCE, not calibration-valid)

All four ON runs (2, 3, 6 and 7) show the same pattern:

| Quantity | Range across runs |
|---|---|
| Heap insert-page acquisitions on the shared last-insert candidate | ~98% |
| Heap-page hold per row | 3.4–5.2 µs |
| … of which waiting for `LogToFile` while holding the page | 33–57% |
| … of which holding `LogToFile` (instrumented append) | 2–5% |
| … residual `self` | ~40–60% |
| `LogToFile` measured hold, whole run | 0.10–0.20 s |
| `LogToFile` measured wait, whole run | 1.1–2.95 s |
| Largest single sampled heap-page holds | 9.5–21 ms, almost entirely `log_wait` |

Readings:

1. **The ledger misses `LogToFile`'s real holders.** With four writers, waiters can account for at
   most about 3× the hold. Measured wait is 10–20× the measured hold. Either most of the
   occupancy comes from uninstrumented `synchronized (this)` sections, or handoff latency
   dominates. The prime suspect is SOURCE-PROVEN: `LogToFile.flush()` takes the monitor and, as
   the flush leader, calls `logOut.flushLogAccessFile()` or `switchLogBuffer()` inside it. That is
   write I/O, or a dirty-buffer flush, under the global monitor, at every commit (every 100 rows
   here). None of it is instrumented.
2. **The 10–21 ms holds are I/O stalls inherited by the heap lane.** A writer holding the shared
   heap target blocks on the log monitor while another thread does commit I/O inside it, and every
   other inserter queues behind the heap page. This is the nested-serialization mechanism, observed
   directly at 4 clients. The I/O that drives it is on the commit path, not the append path. This
   makes **R3a** (no I/O under the monitor, extended to `flush()`) the first log-side step.
3. **Lane utilization is plausible but not proven.** If the candidate pages form one serial lane,
   the summed hold gives U ≈ 0.58–0.88. But the observed first-to-last spans of sampled pages
   overlap: up to ~90 concurrently "alive" pages, because pages are revisited. So span overlap can
   neither prove nor disprove a single lane. A lane-level measurement is needed (§O).
4. **The summarizer's "top resources" list is misleading.** It ranks individual pages, whose U is
   about 0.08, above the lane, which carries over 90% of heap holds. It should roll up by lane and
   by monitor.

### P.4 Implementation findings

| # | Severity | Finding | Fix |
|---|---|---|---|
| 1 | **High** | `LogToFile` holders are incomplete: `flush()` and its `finally`, checkpoint and log-switch blocks, and `synchronized` getters are untimed. The P.3 wait/hold ratio shows the gap matters. | Instrument every monitor entry, and add a release→acquire handoff-gap counter. |
| 2 | **High** | Commit flush and fsync are not attributed at all (§O required leader/follower, wait and sync). | Count flushes, leader vs follower, waiters per flush, and write/sync duration in and out of the monitor. |
| 3 | **High** | There is no lane-level heap target measurement, so heap per-page rows cannot be combined. | Keep a per-container gauge of held candidate pages, and accumulate the time at depth ≥1 and ≥2. |
| 4 | Medium | Sampling removes clock reads and adders only. Every boundary still does a `ThreadLocal` lookup plus linear scans of owned pages, so the overhead floor does not scale with `sampleEvery`. | Acceptable for a diagnostic. Measure it with a microbenchmark of disabled, enabled-unsampled and sampled modes. |
| 5 | Medium | Sampled intervals are inflated by their own `nanoTime` calls, and the estimate scales the inflation by `sampleEvery`. | Count ticks per sampled interval, and bound the inflation with a measured `nanoTime` cost. |
| 6 | Medium | The `F08` document's "70% of the inverse-throughput budget" equals U ≥ 0.7 for one instance. It is consistent with the revised §K, but it is still a utilization criterion, not a prediction. | Use the §K three-part test. |
| 7 | Low | The resource registry has fixed 4096 buckets, is never pruned, and builds a string identity on every page instantiation. With 6.4k resources this is fine, but it grows with page-cache churn. | Fine for a diagnostic; note it in the doc. |
| 8 | Low | Diff hygiene: misaligned braces and indentation in Derby-derived files (`BasePage`, `FileContainer`, `LogToFile`, `ControlRow`). | Reformat before committing, to keep upstream diffs reviewable. |

What is done well: a `static final` flag with no clock reads when disabled; whole-interval
Bernoulli sampling that inherits into nested sections; a correct `Object.wait` suspend/resume of
monitor hold; the priority-based disjoint partition with an independent hold; INVALID markers in
place of store failures; separate JVMs for the enabled, sampled and disabled qualification runs;
and a scoped reset/snapshot around the measured window.

### P.5 Effect on the plan

- **New step before R0 can be trusted:** find the 0.57× Heap-vs-Derby gap (P.2). Any Delos-only
  cost inside the heap-page hold is multiplied by the lane's saturation, so it may be the cheapest
  large win available. It is also the only step that bears directly on the Heap goal.
- **R3a moves ahead of R1**, if finding P.4-1 confirms commit-flush I/O under the monitor. Moving
  the leader's write out of `synchronized (this)` is a local change that keeps the WAL format
  intact, so it preserves Derby compatibility. It would remove the millisecond stalls the heap
  lane inherits.
- **R1 stays next**, if a holder-complete ledger shows the lane saturated with mostly ordinary
  append and handoff waits.
- **R2 is de-prioritized.** The root does not nest the WAL append (P.1). Its remaining cost is
  child acquisition and `allocCache`, which the PK cells must still quantify.

---

## Q. Direct attribution with JFR (revision 4)

### Q.1 Method

The ledger's calibration could not be resolved on the laptop (§P.2). These measurements use JFR
attribution plus a minimal embedded probe instead, so they do not depend on the custom
instrumentation or on throughput being stable. JFR records each lock wait and each I/O call with
its duration and stack. Laptop noise changes how long things take, but not which lock or call
the time is attributed to.

- **Probe:** `F08Probe.java`, about 50 lines. It runs the F08 BARE INSERT_100 shape: table
  `(id int not null, category, bucket, quantity int, payload varchar(4096))` with a 128-character
  payload. Each client inserts 250,000 rows in its own ascending id range and commits every 100
  rows. The probe uses one JVM, embedded, durable defaults, and runs no harness.
- **Engines:** Delos Heap at HEAD `e636ddb7` plus the uncommitted ledger (ledger off, which is the
  default), and upstream Derby 10.17.1.0. JDK 25.0.3, macOS, Apple SSD.
- **JFR settings:**
  - Probe defaults: `JavaMonitorEnter`/`Wait` at a 50 µs threshold, every `FileWrite`/`FileForce`.
  - 8-client runs: monitor events at 10 µs, and execution samples every 2 ms.
- **Not covered here:** the PRIMARY_KEY_ONLY shape, Gen2, and DRDA/server mode.

### Q.2 Sync regression on macOS (SOURCE-PROVEN + MEASURED)

**The source change.** Commit `6e35fbea` (2026-07-22, "Modernize shared RawStore page I/O with
positional channels") changed `delosdb-engine/.../impl/io/DirRandomAccessFile.sync()`:

```text
Derby 10.17:  getFD().sync()          → fsync(2)
Delos HEAD:   channel.force(true)     → on macOS: fcntl(F_FULLFSYNC), a drive-cache flush
```

**Measured cost on this host:**

| Call | Cost per write+sync (100 iterations, twice) |
|---|---|
| `fd.sync()` | 0.03–0.05 ms |
| `channel.force(true)` | 4.2–4.3 ms |

**Where these syncs land.** `LogToFile.flush()` calls `switchLogFile()` inside its own
`synchronized (this)`, and each switch performs two syncs: one in `preAllocateNewLogFile` and one
in `initLogFile`. Checkpoints add container `forceFile` and `writeControlFile` syncs. Both engines
switch about 190 times per million rows, since both runs ended at log file 190–195. So the number
of switches is the same, and only the cost per sync differs.

**JFR evidence, 4 clients, default settings:**

| | Delos | Derby |
|---|---|---|
| Throughput | 200k rows/s | 338k rows/s |
| Contended `LogToFile` monitor entries | 627, avg **3.08 ms**, P90 10.1 ms | 634, avg **0.34 ms**, P90 0.72 ms |
| Log-switch syncs (`FileForce`) | 187 × 4.6 ms + 187 × 4.5 ms ≈ **1.7 s** | not visible (`fsync` is not a JFR `FileForce` event) |
| Checkpoint/container syncs | ≈ 1.1 s | not visible |

**Confirmation by removing switches and checkpoints.** Setting `derby.storage.logSwitchInterval`
and `derby.storage.checkpointInterval` to 128 MB removes nearly all switches and checkpoints:

| Run | Delos | Derby | Delos/Derby |
|---|---|---|---|
| 4 clients, defaults (2 runs) | 204–205k | 350–353k | 0.58 |
| 4 clients, rare switch/checkpoint (3 runs) | 343–371k | 360–368k | 0.95–1.0 |
| 8 clients, rare switch/checkpoint (3 runs) | 337–351k | 365–397k | ~0.89 |

**Consequences:**

- **§P.2 and §P.3 are corrected.** The hidden `LogToFile` holder is `switchLogFile` and its
  syncs inside `flush()`, not the commit write itself. The "≥1.4 µs per row added by Delos" in
  §P.2 is withdrawn: the per-row work is essentially equal (Q.3).
- **The laptop instability is probably this effect.** The 10–21 ms heap-page stalls in the
  ledger, and much of the Heap-only throughput variation, fit this mechanism. Full-flush latency
  on macOS varies, and Derby never issues it. INFERENCE.
- **On Linux,** `force(true)` and `fsync` both map to `fsync(2)`, so this gap should not appear.
  The laptop overstates it.
- **This is a durability decision, not only a performance one.** `F_FULLFSYNC` is the stronger
  guarantee. But commit records reach disk through `rwd` writes, which on macOS do not flush the
  drive cache either. The stronger sync at switch time therefore protects little that commit
  durability relies on. Choose deliberately:
  1. Restore `fd.sync()` semantics. This matches Derby and is equivalent on Linux.
  2. Keep `force(true)` and take preallocation and switching out of the log monitor (R3a), for
     example by preallocating the next log file in the background.

### Q.3 What limits concurrent inserts with the sync stalls removed (8 clients, BARE)

**Where writers wait** (JFR `JavaMonitorWait` on writer threads; 8 threads × ~5.7 s ≈ 45 thread-s
for Delos, 8 × ~5.0 s ≈ 40 thread-s for Derby):

| Wait site | Delos | Derby |
|---|---|---|
| Heap page latch (`StoredPage`, `BaseContainer.latchPage`) | 990k waits, **27.9 s (≈61%)** | 962k waits, **24.5 s (≈61%)** |
| Allocation page latch | 0.49 s | 0.23 s |
| Lock manager (`ActiveLock`) | 0.25 s | 0.26 s |
| `LogToFile.flush` wait | 0.07 s | 0.07 s |

- **The shared heap insert page is the limiting resource.** The probe takes about one latch wait
  per two rows, and it is the only significant wait. Both engines are flat from 4 to 8 clients.
- **The single page is inherited from Derby.** Upstream 10.17 has a 4-page `switchToMultiInsertPageMode`,
  but its call in `getPageForInsert` is commented out (`/* switchToMultiInsertPageMode(handle); */`,
  removed as dead code in `4015b37b`). Both engines therefore insert through one page per table.
  SOURCE-PROVEN, checked against upstream bytecode and the original import `aad50e84`.

**What occupies that page.** These are CPU samples whose stack contains `BasePage.insert*`, i.e.
taken while the heap page is latched. That is 52% of writer samples in both engines:

| Work under the heap page latch | Delos | Derby |
|---|---|---|
| Building the log record (`InsertOperation` → `StoredPage.logRow` → `SQLChar.writeUTF`, `LogRecord.setValue`) | **73.5%** | **70.0%** |
| Applying to the page (`doMe`) | 15.4% | 18.5% |
| Record lock | 5.7% | 7.7% |
| WAL append (`LogToFile.appendLogRecord`) | 2.2% | 1.3% |
| Other | 3.2% | 2.5% |

**Caveats.** There were about 1,600 writer samples per engine, and the categories come from a
stack-matching heuristic. The two engines agree closely, which supports the attribution.

**Consequences for the model in §A and §F:**

- **Nested serialization exists but is not dominant once syncs are removed.** The log monitor
  costs 1–2% of the heap-page hold. Its earlier importance came from the switch-sync stalls
  (Q.2), not from ordinary appends.
- **The serialized section is long because of CPU work inside it,** mostly serializing the row
  into the log record. It is not long because of waits nested inside it.
- **This partly reverses §N item 3 and §G for BARE.** Moving serialization out of the page hold
  *would* matter, because serialization is about 70% of it. But the current RCM-1 implementation
  does not achieve that (Q.4).

### Q.4 RCM-1 with the flag on (8 runs, rare switch/checkpoint, Delos only)

| Clients | Flag off | `rawStorePreparedInsert.enabled=true` |
|---|---|---|
| 4 | 297k, 331k, 306k | 323k, 328k, 305k |
| 8 | 309k, 295k, 335k | 330k, 322k, 325k |

There is no difference beyond noise. This batch ran about 10% slower than the Q.2 batch, which
reflects host variation. The result is consistent with §G: the current implementation still
builds the logged record under the latch. A prepared mutation that moves `logRow` serialization
before latching, and leaves only page-dependent fields (slot, record id, page offsets) under the
latch, remains a valid but unbuilt design. INFERENCE.

### Q.5 Revised plan

| Step | Status | Reason |
|---|---|---|
| Sync decision (Q.2) | **First** | Removes about 40% of laptop throughput and most of the instability. It is a prerequisite for meaningful macOS measurements. |
| PK shape, same probe | **Next** | Determines whether the root or the leaf adds a second hot resource once the heap lane is relieved. |
| **R1, writer-affine heap targets** | **Primary scaling lever** | Removes the only significant shared wait for BARE (≈61% of writer time) and lets serialization run in parallel. The insert-page choice is not part of the on-disk format, so it stays Derby-compatible. |
| Prepared mutation, done properly | Secondary | Shortens the hold by up to about 70%, but keeps a single lane. Useful mainly if R1 is constrained, for example by space-reuse rules. |
| R3a (no I/O under the log monitor) | Only if `force(true)` is kept | Otherwise it is covered by restoring `fsync`. |
| R2 (optimistic descent) | Not supported for BARE | Pending the PK result. |
| R3b (lock-free WAL reservation) | Not supported | The append is 1–2% of the critical section. |
| RCM-R0 ledger as a gate | Retired | JFR answered the attribution question. Keep the ledger only for quantitative tracking. |

**Reproduction.** The probe source, the JFR configuration and the analysis scripts were left in the
reviewer's session scratchpad and are not committed. To reproduce:

1. Run `F08Probe <jdbc-url> <clients> 250000` against `build/libs/*.jar` and against the upstream
   Derby jars in `build/tmp/upstream-derby-network-server-runtime/`.
2. Add `-XX:StartFlightRecording` with monitor thresholds of 10–50 µs.
3. Add `-Dderby.storage.logSwitchInterval=134217728 -Dderby.storage.checkpointInterval=134217728`
   for the no-switch runs.

---

## R. Insert lanes, cross-engine static comparison and held-intent regrant (revision 5)

### R.1 What changed since revision 4

- **R1 landed as per-writer heap insert lanes** (`edf18924`, qualified in `3ec28668`).
  - On contention for the shared insert page (a NOWAIT latch fails and a WAIT succeeds), a
    container switches to a per-thread array of insert targets.
  - Only the in-memory page choice changes. The on-disk format, logging and recovery are untouched.
  - Qualification: `HeapInsertLaneTest` covers commit, rollback, clean restart, crash recovery in a
    separate JVM and an engagement assertion. derbyStoreTests, delosRecoveryTests and
    delosConcurrencyTests show the same results with lanes on and off.
- **The probe is now committed** (`tools/diagnostics/insert-probe/`, extended in `f6919f2a`). It
  supersedes the scratchpad reproduction note in §Q.5.
  - It provides BARE, PK and INDEXED shapes (PK plus two low-cardinality secondary indexes, the
    Docker benchmark's table shape).
  - The JFR attribution now counts monitor waits, monitor entries and `j.u.c.` parks. Revision 4
    counted only `JavaMonitorWait`, which under-attributed `FileContainer.pageValid`.
- **Probe results.** Embedded, 8 writers, rare log switch, rows/s:

  | Shape | Before lanes | Lanes | Upstream Derby |
  |---|---|---|---|
  | BARE | 339–345k | 833–881k | ~370k |
  | PK | 263–272k | 341–357k | 274–316k |
  | INDEXED | ~143k | 155–168k | 163–166k |
  | INDEXED + root routing snapshot | — | 204–209k | — |

  The root routing snapshot is enabled with `delosdb.experimental.btreeInsertRootRoutingSnapshot.enabled`.

- **Docker FULL_INDEXED, 8 clients, rows/s:**

  | Workload | Delos Heap | Delos MVCC | Derby | H2 | PostgreSQL | MariaDB |
  |---|---|---|---|---|---|---|
  | INSERT_100 | 78k | 30k | 74k | 209k | 338k | 300k |

  The standard workloads show no regression from lanes.
- **After lanes, the waits moved to global monitors.** With lanes on, the BARE blocking breakdown
  is:
  - the `allocCache` monitor in `pageValid`: 10.6 s;
  - `LogToFile.appendLogRecord`: 3.8 s.

  INDEXED shows B-tree page latches at 14.0 s and the log append at 8.0 s, versus 2.0 s in Derby.
  With the root routing snapshot, INDEXED page-latch waits fall to 4.6 s.

### R.2 Lock-free page validity: negative result (branch `page-validity-fast-path`, `4870305c`, not merged)

- **Design.** Page fetches read a published, immutable view of the allocation-cache extent ranges.
  Every invalidation and reset withdraws the view under the monitor, before allocation state can
  change. Readers re-validate the view after the lookup, with an acquire fence in StampedLock
  style, and fall back to the monitor. Unlike `655fc302`, no bitmaps are copied.
- **Correctness.**
  - New concurrent allocation, deallocation, reuse and in-place compress test passes.
  - Suites show only the known failures.
  - Without the withdrawals, the engine cannot create a database, so the rule is exercised by
    every test.
- **Effect** (BARE, 8 writers):
  - `pageValid` blocking: 7.8 s → 3.3 s.
  - Total writer blocking: 17.4 s → 13.0 s.
  - **Throughput: unchanged** at 8 and 16 writers for every shape. BARE was 765k with the view
    versus 775k without; PK 317k vs 325k; INDEXED 153k vs 156k.
  - The freed time reappeared as log-monitor and lock-manager waits.
- **Lesson.** Wait time on a resource does not show that it limits throughput. This is the same
  reason `655fc302` was rejected. The experiment is kept on a branch.

### R.3 Static cross-engine comparison of the per-row insert path

Source read: Delos `f6919f2a`, PostgreSQL master, MariaDB `server-main` (InnoDB), H2 master,
Firebird 5.0.4, HerdDB master. All SOURCE-PROVEN unless marked.

| Per row | Delos (Derby) | PostgreSQL | InnoDB | H2 | Firebird / HerdDB |
|---|---|---|---|---|---|
| WAL append | `synchronized(LogToFile)` per record: 1 / 2 / 4 records per row (BARE/PK/INDEXED), plus Begin/End per commit and 4 per page allocation. It does a ~100 B copy, a CRC32 of each 32 KB buffer on switch, and may `wait()` for buffer flushes and `write` while holding the monitor (LogToFile.java:3851, LogAccessFile.java:517/590/783). | Record built and CRC'd outside any lock. The global spinlock covers `CurrBytePos += size` only (xlog.c:1172-1180). The copy runs 8-way under per-backend insertion locks. Flushers scan `insertingAt` (xlog.c:1545). | The mtr builds redo privately. It takes `log_sys.latch` **shared**, reserves with one `fetch_add` (mtr0mtr.cc:1072) and copies in parallel. Only the log writer takes the latch exclusively, to swap buffers. | No WAL. A per-transaction private undo map; the background store writes chunks. Commit is **not** durable by default (`WRITE_DELAY` 500 ms). | Firebird: no log; careful writes at commit. HerdDB: callers only enqueue, and one spool thread serializes and batches the fsync. |
| Lock manager per row | **1 / 3 / 7 container-intent requests** on shared entries (heap CIX ×1/2/4: once per open, and `B2IController` reopens the heap per index; index CIX ×0/1/3), plus 0/1/3 previous-key locks and the new-row lock. Each request takes the entry `ReentrantLock` and scans granted locks linearly (ConcurrentLockSet.java:211/989, LockControl.java:271). | None. A new tuple is protected by xmin. Relation locks hit the backend-local hash after the first, or a per-PGPROC fast-path slot (lock.c:891/998). | None. `DB_TRX_ID` is an implicit lock (lock0lock.cc:5942). The table IX is taken once per transaction (row0ins.cc:3822). | None. The value carries the owner `operationId`. The table lock is a CHM `containsKey` after the first. | Firebird: none, and the relation lock is cached per transaction (rlck.cpp:112). HerdDB: one per key in a striped CHM. |
| B-tree descent | Exclusive root latch, with the child latched while the root is held (BranchControlRow.search:291). The routing snapshot is off by default. | Shared locks, parent released before child (Lehman-Yao). Rightmost-leaf fastpath. | S-latch coupling, `index->lock` S. The leaf is X-latched. | One CAS per index root (copy-on-write). | Firebird: root S-latched. |
| Page fetch validity | `synchronized(allocCache)` per `getUserPage`: about 1 / 3–4 / 7–10 per row. | Partitioned mapping lock taken shared, plus a pin CAS. | page_hash cell latch. | n/a | Firebird: lock-free CDS hash. |
| I/O under a global lock | Log buffer starvation, log switch, preallocation fsync under the container monitor (RAFContainer.java:685). | None. | None. | None. | None. |

INFERENCE: every comparison engine that beats Delos on concurrent inserts has two properties.
It never runs per-record work inside an exclusive global section. It never makes a
shared lock-manager call for a freshly inserted row.

### R.4 Held-intent regrant (branch `held-intent-regrant`)

**Change.** `AbstractPool.lockObject` grants a CIX container lock that the transaction already
holds in the same group from its own `LockSpace`, without the lock table
(`LockSpace.regrantHeld`). Disable with `-Ddelosdb.locks.heldIntentRegrant.disabled=true`.

**Why it is exact.**
- `LockControl.addLock` always grants a re-request from a space that already holds the lock,
  even with waiters queued, and only increments its count.
- The regrant increments both the group copy and the lock-table lock (`Lock.grantedLock`).
- So counts, group release (`unlockGroup` uses the copy's count), partial unlocks, transfers and
  escalation behave exactly as before.
- **Correction (`61a766a8`).** As merged in `a9b76d61`, the argument also claimed that "a
  granted lock's count is written only by its owning space". That was DISPROVEN: a compatibility
  space can be used by several threads with different groups. The regrant (under the
  `LockSpace` monitor) and lock-table grants and releases (under the entry's lock) then updated
  the same count under different locks. That could lose increments and release the table lock
  early. `Lock.count` is now updated atomically (`incrementCount`, a CAS loop in `unlock`) on
  every path, and `HeldIntentRegrantTest` has a concurrent-group regression test.

**Tests.** `HeldIntentRegrantTest` covers:
- N requests needing N unlocks;
- no regrant across groups, and regrant after transfer;
- regrant while a CX request is queued, with the waiter granted when the group is released;
- indexed inserts holding IX and releasing it at commit and rollback;
- escalation to table X mid-transaction;
- `SYSCS_CHECK_TABLE`.

Removing the lock-table count increment makes all four tests fail, including sanity
assertions. The store, recovery and concurrency suites are covered in the commit message.

**Effect.** Embedded, 8 writers, rare log switch, three alternating pairs, rows/s:

| Shape | Regrant on | Regrant off |
|---|---|---|
| BARE | 976k / 910k / 882k (avg 923k) | 727k / 806k / 732k (avg 755k) → **+22%** |
| PK | 334k avg | 337k avg |
| INDEXED | 162k avg | 161k avg |

### R.5 Corrections and what the regrant result shows

- **The log monitor is not a hard cap at the INDEXED record rate.** The cross-check initially
  inferred a serial log point from a near-constant record rate: BARE ≈890k, PK ≈650k and
  INDEXED ≈620k records/s. BARE now sustains ≈1.06M records/s, so PK and INDEXED are limited
  below the log's capacity. DISPROVEN as a cap; the monitor still accounts for large wait times.
- **PK is limited by B-tree page latching.** The PK profile with regrant on shows:
  - total writer blocking 37.7 s;
  - `StoredPage` latch waits 22.4 s;
  - `LogToFile.appendLogRecord` 8.9 s;
  - `pageValid` 4.3 s.

  `jfr print` failed partway, so these are lower bounds. With latch-free root routing, INDEXED
  already gains 22–25% (§R.1). This points to R2: optimistic or shared descent, then leaf
  hot-spot relief.
- **R3b changes from "Not supported" to "Later".** Revision 4 rejected it because the append was
  1–2% of the heap-latch hold. With lanes, the log monitor is the second-largest wait for PK and
  INDEXED. It also has the I/O-under-monitor paths listed in §R.3.

### R.6 Revised plan

| Step | Status | Reason |
|---|---|---|
| R1 insert lanes | **Done** (`edf18924`) | BARE 2.5×. |
| Held-intent regrant | **Done on branch** | BARE +22%. After a transaction's first row, it removes all container-intent lock-table requests (1 / 3 / 7 per BARE / PK / INDEXED row); the 0 / 1 / 3 previous-key requests remain. |
| Sync decision (Q.2) | **Open, user decision** | Still dominates default macOS runs. |
| **R2: qualify the root routing snapshot, then shared/optimistic descent** | **Next** | PK/INDEXED are page-latch bound; +22–25% already measured with the existing flag. Needs concurrent-split tests and the fallback-rule fix (§H). |
| Previous-key / new-row lock elision for inserts | Candidate | PG, InnoDB, H2 and Firebird take no per-row lock-manager call for a fresh row. Derby's isolation semantics (phantoms and RR previous-key locking) need a careful design. INFERENCE. |
| R3b: reserve-then-copy WAL append, plus R3a | Later | Second-largest wait after lanes. Recovery and ordering work is substantial. |
| `pageValid` lock-free view | Parked (`4870305c`) | Correct, but no throughput effect today. Re-measure after R2. |

---

## S. Revision 6: insert root routing qualified and on by default

### S.1 Change

- B-tree inserts route through the root routing snapshot (`BTree.searchFromRootRoutingSnapshot`)
  instead of latching the root. This snapshot is the one keyed reads already use on main
  (`BTreeScan.searchFromRoot`). The flag is now on by default;
  `-Ddelosdb.btree.insertRootRoutingSnapshot.disabled=true` restores the latched-root descent.
  SOURCE-PROVEN.
- The path already follows §H's rule 1:
  1. The child is latched with a waiting `ControlRow.get` while nothing is held.
  2. Then the snapshot identity is re-checked, once after the child latch and again after the
     coupled descent below it.
  3. A missing or changed snapshot falls back to the latched descent, which republishes it.
- Why a current snapshot gives the right child: a split of the child updates the root while the
  child is still latched (`LeafControlRow:603-700`). The root update calls `pageAboutToChange`
  before any byte changes (`StoredPage.logAction`). So a reader that latches the child after the
  split sees a new snapshot identity. A reader that latched the child first holds off the split.
  Splits, `B2IUndo.findUndo` and recovery stay on the canonical path.

### S.2 Correctness defect found and fixed: stale snapshots on a second BTree instance

- **Defect.** SOURCE-PROVEN, reproduced.
  - A root page records a single snapshot owner (`ControlRow.rootRoutingOwner`), and a page change
    invalidates only that owner.
  - One index can have several live `BTree` instances, because each root control row decodes its
    own (`ControlRow.getConglom`). After the root page is evicted and reloaded, two paths get the
    new instance:
    - logical undo (`B2IUndo:139`);
    - plans compiled after a conglomerate cache miss (`B2IFactory.readConglomerate`).
  - Cached conglomerates and older compiled plans keep the old instance: plans hold it through
    `B2IStaticCompiledInfo`.
  - Each instance's latched descent takes over the owner field. The instance that lost ownership
    keeps a snapshot that nothing invalidates any more.
- **Effect with inserts routed this way.** Rows land in leaves that do not cover their keys.
  - InsertRootRoutingSnapshotTest without the fix (DelosDB sanity build):
    - The concurrent test, with rollbacks and a 1000-page cache, trips the sanity assert "last row
      on left page 98 > first row on right page 99".
    - The second-instance test loops forever: the insert reaches a full leaf that does not cover the
      key, the split descends correctly and fixes another leaf, and the retry returns to the same
      full leaf.
- **Exposure on main.** Keyed reads route through the same snapshot unconditionally, so they are
  exposed to the same mechanism (INFERENCE). A run with inserts on the latched path plus the
  defect, 4 runs with a point lookup per key, did not reproduce missed reads.
- **Fix.** A change of owner invalidates the previous owner's snapshot first. This applies to the
  root, branch and leaf-read snapshots. Owners change only under the page's exclusive latch, as do
  the page changes that invalidate them. So at any point only the owner can hold a snapshot of the
  current page image.
  - Cost: two instances used alternately invalidate each other's snapshot, so they fall back to
    the latched descent more often. The default single-instance workload is unaffected.

### S.3 Measurements

Embedded probe, 8 writers, rare log switch, 2M rows, 3 alternating pairs; every run verifies the
row count, distinct keys, min and max.

| Shape | Snapshot on (rows/s) | Off (rows/s) | Change |
|---|---|---|---|
| PK | 395k / 339k / 374k (avg 369k) | 284k / 285k / 289k (avg 286k) | **+29%** |
| INDEXED | 176k / 170k / 161k (avg 169k) | 150k / 150k / 148k (avg 149k) | **+13%** |
| BARE (no index) | avg 752k | avg 787k | flag not involved; run-to-run noise |

- The earlier "+22–25% INDEXED" (§R.1) came from a different stack and baseline. On the current
  main (lanes plus regrant) the gain is +13% INDEXED and +29% PK.
- INDEXED gains less because its three secondary indexes still couple from level 1 down. It also
  has more leaf hot spots: monotonic keys append to the rightmost leaf of every index.

### S.4 Qualification

- InsertRootRoutingSnapshotTest: 8/8 repeat runs, plus the suite run. Without the owner fix, the
  concurrent test fails in about 2 s and the second-instance test hits its 3-minute timeout.
- derbyStoreTests 435/435. delosRecoveryTests 57/57.
- delosConcurrencyTests 58: DEL-ISO-001, DEL-FK-004 and the task-only F08TransactionStatus test
  fail as on main. DEL-TRAVERSAL-001 and BTreeIndexConcurrencyArchitectureProofTest pass.
- Committed on branch `insert-root-routing` as `097a13e5`. Not merged.

### S.4a Later fixes on main (`61a766a8`)

- **Held-intent regrant counts.** These are now atomic across threads that share a compatibility
  space (see the R.4 correction).
- **JFR insert attribution.**
  - Before the fix, `jfr_insert_attribution.py` counted B-tree leaf insertion CPU as heap work,
    because both run under `BasePage.insert*`. Samples are now classified by their heap or B-tree
    caller, and unknown callers are reported separately.
  - What this affects: CPU splits for PK and INDEXED recordings made before the fix.
  - What it does not affect: §Q.3, which is BARE with no B-tree, and the latch and monitor wait
    totals in §R.5.
- **Current numbers.** Medians of 8-writer runs reported with the fix are 886k (BARE), 455k (PK)
  and 176k (INDEXED) rows/s. These are taken with lanes, the fixed regrant and insert root
  routing.

### S.5 Revised plan

| Step | Status | Reason |
|---|---|---|
| R1 insert lanes | **Done** (`edf18924`) | BARE 2.5×. |
| Held-intent regrant | **Done** (`a9b76d61`) | BARE +22%. |
| R2a: insert root routing on by default, plus the owner fix | **Done** (`097a13e5`) | PK +29%, INDEXED +13%; fixes a stale-snapshot defect that reads on main share. |
| R2b: branch levels and the rightmost-leaf hot spot | **Next** | Re-profile PK/INDEXED first. Candidates: per-level snapshots below the root (lift the `rootLevel == 2` limit of the experimental branch snapshots, with the same owner rule), and relief for the monotonic-append leaf (Postgres fastpath style: cached rightmost leaf with a try-latch check). |
| Sync decision (Q.2) | **Open, user decision** | Still dominates default macOS runs. |
| Previous-key / new-row lock elision | Candidate | Unchanged from §R.6. |
| R3b reserve-then-copy WAL append, plus R3a | Later | Unchanged from §R.6. |
| `pageValid` lock-free view | Parked (`4870305c`) | Re-measure after R2b. |

---

## T. Revision 7: R2b, the WAL append experiment and the log buffer default

### T.1 Sane versus insane builds

- **The build defaults to a sane build.** `delosdb-commons/build.gradle` sets `delosdb.sane`,
  default `true`, so `SanityManager.DEBUG` is compiled in. Upstream Derby release jars are
  insane. SOURCE-PROVEN.
- **The debug code is not free on the hot path.** In a sane INDEXED recording, CPU samples
  inside `LogToFile.appendLogRecord` were spent as follows:
  - 55% in `setEndPosition`, building an `ASSERT` message string eagerly;
  - 21% in `SanityManager.DEBUG_ON`, which does a `ConcurrentHashMap` lookup;
  - 2% in `LogCounter.makeLogInstantAsLong` message strings.

  In the insane build, `appendLogRecord` is 1.3% of writer samples.
- **Consequence.** Profiles and probe numbers in §P–§S came from sane builds, and comparisons
  with upstream Derby (§Q) compared a sane Delos with an insane Derby. Their direction largely
  held; for example, the PK root latch and the allocation-cache convoy were real in both builds.
  But absolute rates and some CPU splits must be re-measured. Use `-Pdelosdb.sane=false` for any
  benchmark or profile.

### T.2 Cached page validity (`6211e32e`, merged)

- **Problem.** `FileContainer.getUserPage` checked allocation state under `synchronized
  (allocCache)` on every page fetch. Every page allocation invalidates the cache, and revalidating
  it latches the allocation page that the allocating thread holds. So one reader waited for that
  latch inside the monitor, and every other heap and B-tree fetch queued behind it. In the sane
  profile this was 7.7 s of 17.2 s writer blocking for PK and 9.9 s of 34.1 s for INDEXED.
- **Change.** A page found in the page cache skips `pageValid`. The existing status check after
  latching decides instead: deallocation changes the page's own status under its latch, and a new
  page stays latched by its allocator until it is initialized. The thread that allocates or
  deallocates a page revalidates the cache when it is done (`allocationCompleted`,
  `deallocatePagenum`).
- **Rejected designs.** The store suite caught both:
  1. **Skip without allocator-side revalidation.** `AllocationCache.trackUnfilledPage` drops
     updates while the cache is invalid, and page fetches had been revalidating it as a side
     effect. Inserts lost track of partly filled pages and allocated new ones:
     `Derby3625Test` saw 5 pages instead of 4, and `longRow` output changed. This variant
     measured +23–32%, but only because allocating fresh pages is cheaper.
  2. **Queue the dropped updates and apply them at the next validate.** A stale "filled" hint
     applied after a page was reused hid an empty page (`Derby3625Test`, even when run alone).
- **Result.** Insane build, 8 writers, 4 alternating pairs: PK +9%, INDEXED +8%, BARE +24%.
  Allocated pages are equal on and off. In the INDEXED profile the allocation-cache wait falls
  from 9.9 s to about 1.2 s.

### T.3 WAL append window (branch `wal-reserve`, `eddac126`, not merged)

- **Motivation.** In the insane build, the `LogToFile` monitor was the largest INDEXED writer
  wait: 20.9 s over 223k blocked entries, averaging 94 µs each. Yet only 1.3% of samples fell
  inside `appendLogRecord`. The cost was monitor convoying, not the copy, so reserve-then-copy
  under the same lock would not help.
- **Design.** A CAS-reserved window on the current log buffer lets appends run without the
  monitor.
  - Every monitor section that uses the current buffer or the log end first seals the window:
    it stops new reservations and waits for in-flight copies.
  - A window on a fresh buffer takes over that buffer's checksum reservation.
  - Sane builds assert that nothing touches the buffer while a window is open.
- **Correctness evidence.** `WalAppendWindowTest` runs small buffers, frequent log switches and
  checkpoints, oversized records and rollbacks, then checks a clean restart and crash recovery.
  - It caught a lost-records bug: the `flush` nested inside `switchLogFile` reopened a window on
    the old log file.
  - It fails 3/3 when sealing skips the drain.
- **Result: no throughput gain** (insane build, 8 writers).

  | Shape | Window on | Window off |
  |---|---|---|
  | INDEXED | 195k | 194k |
  | PK | 492k | 484k |
  | INDEXED, 1M buffer | 221k | 225k |
  | PK, 1M buffer | 498k | 521k |

  Log-monitor blocking fell to 13–15 s, and the waits moved to the B-tree leaf latch. The log
  append runs under that latch, but the latch is what serializes the inserts. **DISPROVEN** as a
  throughput lever on this workload. Kept on its branch with its tests.

### T.4 Log buffer default (`58d6e1b1`, merged)

- **Problem.** Derby's 32K log buffer holds about 40 INDEXED rows. Each buffer switch computes the
  checksum, and may write a dirty buffer, while holding the `LogToFile` monitor.
- **Change.** The default `derby.storage.logBufferSize` is now 1/64 of the maximum heap, between
  32K and 1M. Each database keeps three buffers.
  - A fixed 1M broke `Derby5730Test`, which runs 24 in-memory boots under `-Xmx16M`. It passes
    with 32K, 64K or 256K.
  - The property still overrides the default.
- **Result** (insane build, 8 writers, 1M vs 32K):

  | Shape | Change | Pairs |
  |---|---|---|
  | INDEXED | 220k vs 192k (+15%) | 4 of 4 |
  | PK | 518k vs 443k (+8% without one outlier) | 4 of 4 |
  | BARE | unchanged | — |

### T.5 Current state and plan

- **Embedded probe, insane build, 8 writers, main at `58d6e1b1`.** Rates are about 1.0M rows/s for
  BARE, about 520k for PK and about 220k for INDEXED.
- **INDEXED writer blocking.** The largest wait is the leaf latch reached through the root
  snapshot (`ControlRow.get` from `searchFromRootRoutingSnapshot`), at 12–18 s. Next come the
  log monitor and coupled branch descents (about 2.5 s). The allocation cache is now about 1 s.
- **What the leaf hold contains.** CPU under B-tree page insertion is about 41–52% building the
  log record, 32–49% applying it to the page, and 6–12% the WAL append (insane profiles).

| Step | Status | Reason |
|---|---|---|
| R1 insert lanes | **Done** (`edf18924`) | |
| Held-intent regrant | **Done** (`a9b76d61`, counts fixed in `61a766a8`) | |
| R2a insert root routing | **Done** (`097a13e5`) | |
| R2b cached page validity | **Done** (`6211e32e`) | |
| Log buffer default | **Done** (`58d6e1b1`) | |
| R3b WAL append window | **Rejected for now** (`eddac126`) | No throughput gain (§T.3). |
| Shorten the leaf latch hold (build the log record before latching) | **Next candidate** | It dominates INDEXED. The earlier RCM-1 prepared-insert path had no measured effect under a sane build and the root convoy, so it needs re-measuring on an insane build. |
| Branch snapshots below the root | Candidate | About 2.5 s of coupled descents for INDEXED. |
| Sync decision (Q.2) | **Open, user decision** | |

