# F08 critical-path occupancy ledger

Status: diagnostic only, default off. RCM-1 performance development is paused;
RCM-2/3 and J1/J2/J3 are outside this change. No measurements or optimization
promotion are authorized by a green qualification result alone.

## Live-tree audit

The implementation began on `main`, HEAD `e636ddb7`, with no tracked edits.
`docs/design/RAWSTORE-CONCURRENT-MUTATION-REVIEW.md` was already untracked and is
preserved unchanged.

* `67a2d44d` contains prepared RawStore insertion; `e636ddb7` registers its
  qualification in the permanent stable-suite registry. The enabled property
  still defaults to false. This tranche leaves that implementation and flag alone.
* Transaction-status commits, concurrent commit qualification, maintenance
  materialization, durable dependency/reclamation APIs and a bounded status cache
  are present in the MVCC provider. The default status cache is 4096 slots, with
  durable lookup on a miss. This is source-state confirmation, not a rerun of the
  entire status lifecycle qualification campaign.
* The history includes removals of rejected WAL append and page-validity
  experiments (`4081c114`, `655fc302`) and post-F08-U experiments (`801425ff`).
  Root-routing snapshot support remains. The non-key materialization change at
  `7cf48ef9` is still present; it is not silently backed out here.
* `FileContainer` initializes one `lastInsertedPage` slot. `getPageForInsert`
  tries its shared candidate NOWAIT, then reloads the shared candidate and waits.
  This tranche neither tunes nor reintroduces insert-page fanout.

## Exact ownership boundaries

Paths below are relative to
`delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/`.

| Boundary | Actual authority | Timing and nesting |
|---|---|---|
| `raw/data/FileContainer.getPageForInsert` | Candidate selection, not the exclusive page lock | Scope classifies Heap validation/candidate requests. It does not start a guessed hold timer. `getUserPage` validates allocation, finds the cache page, then requests its latch. |
| `raw/data/BasePage.setExclusive`, `setExclusiveNoWait` | Logical exclusive `owner` on the page; `synchronized(this)` protects its transitions | Attempt starts before the Java page monitor. Ownership starts immediately after `preLatch` assigns `owner`, before observer registration. Existing owner waits release the Java monitor; cleaner waits occur after logical ownership starts and are included in hold. |
| `raw/data/BasePage.releaseExclusive` | Same logical page ownership | Hold ends immediately before clearing `owner`, after observer removal. Nested abort acquisitions/releases do not create another exclusive interval. |
| `raw/data/CachedPage.releaseExclusive`, `StoredPage.releaseExclusive` | Same page ownership until the BasePage release | CachedPage's row-count/container-cache callbacks run before BasePage releases and remain included. StoredPage's page-cache release follows BasePage release and is excluded. |
| `access/btree/ControlRow.get`, `getControlRowForPage` | Container's ordinary RawStore page acquisition | Root requests use the canonical root page number. The actual latched control-row class classifies branch/leaf without faulting extra fields. Cached root level, when already available, supplies maximum observed height; zero means unavailable. |
| `access/btree/BranchControlRow.search` | Parent's exclusive latch, then child's exclusive latch | The child is acquired before `this.release()`. Child latch attempt/wait and allocation-cache access are attributed to the still-held parent. Parent is released before recursive child search. **Normal internal-root insertion does not retain the root until leaf WAL append.** Root-as-leaf and structural paths can differ; counters decide. |
| Every `synchronized(allocCache)` in `raw/data/FileContainer` | That container's `AllocationCache` Java object | Start before monitor entry; acquired timestamp inside; final timestamp in `finally` before exit, including returns/retries/exceptions. Reentrant entries do not count a second exclusive hold. AllocationCache has no synchronized methods of its own. |
| `raw/log/FileLogger.logAndDo` | Transaction-local FileLogger monitor | It is not the shared cross-writer authority. Ordinary calls append then apply the operation with the page still owned. FIRST/LAST additionally synchronize on `logFactory` around append **and** `doMe`. |
| `raw/log/LogToFile.appendLogRecord` | `synchronized(this)` on shared LogToFile | Start before this monitor, stop in `finally` before exit. The outer FIRST/LAST acquisition is timed in FileLogger; append's reentrant entry is not counted twice. Slave pre-mode's non-append return is excluded. |
| `raw/log/LogAccessFile.switchLogBuffer` | LogAccessFile's own monitor, possibly under LogToFile | Time is a nested append-hold category only when LogToFile is actually owned. It includes checksum/sealing/buffer selection. Dirty flushing nested inside it gets its own disjoint category. |
| `raw/log/LogAccessFile.flushDirtyBuffers` | LogAccessFile monitor for queue operations, I/O outside that inner monitor | Its entire interval counts as nested LogToFile occupancy when called under append, including waiting for an in-progress flusher. Releasing/waiting on LogAccessFile does not release LogToFile. |
| `raw/log/LogToFile.switchLogFile` | LogToFile monitor and existing switching protocol | Remaining switch work, including preallocation/sync, gets the switch-file category. Nested buffer switch/dirty flush supersede it. Unqualified `wait()` on LogToFile releases **all** its reentrant monitor levels; instrumentation suspends hold and resumes only after reacquisition, including exceptional interruption. |

These facts confirm the possible Heap-page → append nesting and
root → child/cache nesting. They do not prove either explains F08 throughput.
F08 uses INT keys in distinct ascending client ranges; a shared rightmost leaf
is not assumed.

## Accounting

Enable before the storage classes initialize (sampling defaults to 1-in-32):

```text
-Ddelosdb.diagnostic.rawStoreOccupancy.enabled=true
-Ddelosdb.diagnostic.rawStoreOccupancy.sampleEvery=32
```

The flag is read once into a static final. Disabled boundaries perform no clock
reads, counter updates or thread-local state creation. Diagnostic counters use
LongAdder/atomic operations and a CAS registry; no diagnostic monitor, stack walk,
per-event log, per-mutation event allocation or alternate mutation algorithm is
introduced. Initial resource/thread/counter-stripe creation can allocate. Nesting
arrays are reused and grow only if their initial capacity is exceeded.

Page identity combines PageKey and the owning data-factory identity. The CAS
registry reunites a physical page's counters after cache eviction/reload.
Allocation and log IDs name their actual monitor objects. Caller context denotes
the request/innermost page role, not necessarily an already-held page.
`called_with_*` sampled counts independently record the roles actually held when the
outer append monitor is acquired; multiple roles may apply to one acquisition.
Allocation pages remain OTHER even inside a Heap/root request.

Each thread tracks all its owned pages; parent release need not follow stack
order. A randomized Bernoulli decision at the start of an outer ownership interval
selects the entire interval, including all nested acquisitions. It never starts or
stops timing halfway through a page hold. Unselected intervals retain bookkeeping
for ownership/reentrancy but perform no timer reads or normal counter updates.
`sampleEvery` must be a power of two in 1..1024; 1 retains full accounting.
Log phase durations accumulate in thread-local primitives and publish once at the
outer append release instead of updating shared atomics on every transition.
For a selected interval, every transition charges elapsed time to one category on each still-owned
page. Priority is append wait/hold, allocation wait/hold, child-latch attempt,
then residual self. Thus allocation work inside child acquisition is counted
once, not as both allocation and child wait. Log hold itself uses the innermost
buffer/flush/file phase, again counting an interval once.

For each page's independently measured elapsed hold:

```text
hold_ns = self_ns + child_wait_ns + alloc_wait_ns + alloc_hold_ns
        + log_wait_ns + log_hold_ns
```

Page switch/flush/file columns are zero: those intervals are already in its
`log_hold_ns`. For LogToFile, ordinary append/FIRST-LAST apply is `self_ns`, and
buffer/flush/file work occupies the remaining disjoint phase columns.
Allocation monitor rows currently report total hold as self.

`self_ns` is **residual unclassified time**, not a CPU measurement. It includes
untracked record locking, page-cache find, mutation construction/application and
release callbacks. `child_wait_ns` times the actual RawStore latch attempt,
including page-monitor entry; cache-find waits stay residual. Further diagnosis
is needed if that residual dominates.

Do not add page, cache and log holds together. Nested holds are already inside
outer page occupancy. Do not add unrelated page instances as if they were one
exclusive lock. Sum contexts of the **same** instance when deriving its overall
utilization.

Counts and times remain raw sampled observations in CSV. `sample_every` records
the inverse selection probability; the summarizer emits separately labelled
`estimated_*` values multiplied by that weight. These are estimates with sampling
variance, not exact counts. A short-lived page may have no selected acquisition or
only one; its first/last timestamps bound observed samples, not its full lifetime.

Acquisitions/holds are published at final page release. Snapshot/reset require
quiescent writers; resetting during active ownership is unsupported. An unmatched
release, such as an ownership transfer between threads, produces an INVALID
marker and invalidates evidence rather than introducing a store failure.
First-acquire/last-release timestamps are offsets from a common diagnostic
monotonic origin. No client/transaction identifiers or stack traces are captured.

`RawStoreOccupancy.snapshot()` supplies CSV lines; disabled mode returns no
lines. An optional shutdown export uses
`-Ddelosdb.diagnostic.rawStoreOccupancy.report=/absolute/existing-parent/report.csv`.
Shutdown exports include boot/fixture/cleanup and are unsuitable for measured-run
utilization. The campaign below uses scoped reset/snapshot instead.

## Qualification

```bash
. ~/Development/jdk25-env.sh
./gradlew :delosdb-tests:runDelosRawStoreOccupancyEnabled \
  :delosdb-tests:runDelosRawStoreOccupancyDisabled \
  :delosdb-tests:runDelosRawStoreOccupancySampled \
  -Pdelosdb.sane=false --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/diagnostics -p 'test_*.py'
```

The Java qualification checks concurrent real SQL inserts, commit/rollback row
counts, Heap/root/cache/log activity, independent outer-hold partition equality,
nonnegative intervals, reentrant monitor accounting, real Object.wait suspension,
parent-before-child release, exact four-thread aggregation and quiescent reset.
Enabled-full (`sampleEvery=1`), sampled (`sampleEvery=32`), and disabled tasks run
in separate JVMs. Sampling qualification checks four threads with 40,000 outer
intervals and asserts equal parent/child/log sample counts and complete partitions. The disabled run returns no report.
The test is registered in the permanent concurrency/full suite.

The Python qualification rejects incomplete campaigns, malformed/overlapping
partitions and an instance's occupancy exceeding the measured wall window.

Required repository gates (baselines stay unchanged):

```bash
./gradlew :delosdb-tests:compileTestJava \
  delosRepositoryIntegrityStaticAnalysis \
  :delosdb-tests:delosTestProvenanceStaticAnalysis \
  :delosdb-tests:delosStableTestSuiteStaticAnalysis \
  :delosdb-tests:delosIsolationSpecStaticAnalysis \
  -Pdelosdb.sane=false --rerun-tasks --console=plain
```

## Revised calibration after the first campaign

The first full-accounting campaign completed but failed all four calibration
cells. Its 64 measurement windows and 16 ledgers were structurally valid. BARE
Heap throughput changed -28.7%/-8.0%; PK changed +10.1%/+8.7% while the uninstrumented
Derby reference changed +36.3%/+21.3%. The architecture model remains unproven.
Original evidence under `f08-occupancy` is preserved.

The 1-in-32 BARE/4-client preflight also failed calibration: pooled median Heap
ON/OFF was 0.9430 (-5.70%), Derby reference drift was +2.60%, and the normalized
ratio was 0.9191. Adjacent Heap pairs ranged from -20.46% to +37.57%, indicating
substantial dispersion. No wider run followed this failure. The result does not
confirm or falsify the model, or isolate the remaining gap as diagnostic cost.
See `build/reports/delosdb/benchmarks/f08-occupancy-sampled-calibration/` for raw
windows, weighted/raw summaries, qualification and the assessment. Whole-workload
calibration does not eliminate per-interval timing bias in sampled observations.

The revised task writes to `f08-occupancy-sampled` by default. A validated
`-Pdelosdb.occupancy.reportTag=sampled-calibration` keeps the small preflight separate
from any later full campaign. The harness replaces its own report/database directory
on execution; select a fresh report tag when preserving a previous run.

Run only BARE at 4 clients first, with OFF–ON–ON–OFF repeated twice (eight runs,
four per mode). Both engine orders are balanced within each mode. The reference
Derby worker stays uninstrumented in every run; `calibration_mode` distinguishes
the time-matched cohorts. Aggregate harness medians mix modes and must not be used
as the calibration result.

```bash
. ~/Development/jdk25-env.sh
./gradlew :delosdb-tests:runDelosF08OccupancyCampaign \
  -Pdelosdb.sane=false -Pdelosdb.occupancy.shape=BARE \
  -Pdelosdb.occupancy.enabled=paired -Pdelosdb.occupancy.clients=4 \
  -Pdelosdb.occupancy.sampleEvery=32 \
  -Pdelosdb.occupancy.reportTag=sampled-calibration --console=plain
python3 -B tools/diagnostics/summarize_rawstore_occupancy.py \
  build/reports/delosdb/benchmarks/f08-occupancy-sampled-calibration \
  --paired --shape BARE --clients 4 \
  --output build/reports/delosdb/benchmarks/f08-occupancy-sampled-calibration/calibration-summary.json
```

If this preflight fails either 5% gate, stop before wider measurements or any
architecture implementation. Do not infer the model from invalid calibration.
If it passes, the same bounded BARE/PK 4/8-client campaign can use paired mode:

```bash
for shape in BARE PRIMARY_KEY_ONLY; do
  ./gradlew :delosdb-tests:runDelosF08OccupancyCampaign \
    -Pdelosdb.sane=false -Pdelosdb.occupancy.shape="$shape" \
    -Pdelosdb.occupancy.enabled=paired -Pdelosdb.occupancy.clients=4,8 \
    -Pdelosdb.occupancy.sampleEvery=32 --console=plain
done
python3 -B tools/diagnostics/summarize_rawstore_occupancy.py \
  build/reports/delosdb/benchmarks/f08-occupancy-sampled --paired \
  --output build/reports/delosdb/benchmarks/f08-occupancy-sampled/occupancy-summary.json
```

Run from the repository root after qualification. These are fresh **embedded**
Heap and upstream Derby comparisons on the same host, using the existing canonical
SQL fixture/workload, 128-byte payload and normal durable commits. They are not
DRDA/container results and should not be numerically spliced into an older server
campaign. Gen2 is deliberately omitted from this first shared-authority proof;
its independent metadata authorities would complicate attribution. Upstream Derby
is an uninstrumented reference, not a patched upstream source.

The task enforces only BARE/PRIMARY_KEY_ONLY INSERT_100, 4/8 clients (or the 4-client
preflight), zero warmups and one fresh measured interval. Paired mode uses eight
runs; each mode has four observations per cell. Each client inserts
250,000 rows, committing every 100. Reset occurs at the ready barrier before
writers start; snapshot occurs after timed writers finish, before verification or
restore queries. Reports include the exact inserted-row denominator and wall time.

Preflight without executing measurements:

```bash
./gradlew :delosdb-tests:runDelosF08OccupancyCampaign \
  -Pdelosdb.sane=false -Pdelosdb.occupancy.validateOnly=true \
  -Pdelosdb.occupancy.shape=PRIMARY_KEY_ONLY \
  -Pdelosdb.occupancy.enabled=true --console=plain
```

Do not enable RCM-1 or other experimental insertion/routing/WAL flags. Do not tune
any algorithm during the campaign. The task does not run F01–F13.

Evidence destination:
`build/reports/delosdb/benchmarks/f08-occupancy-sampled/<shape>/paired/` (or the explicit report tag).
Each cell has normal harness reports, a measurement-window CSV and, for enabled
Heap, a raw per-instance occupancy CSV. Enabled CSVs include raw acquisitions,
selected-last-insert-candidate counts, failed attempts and their elapsed time,
wait/hold/disjoint phase totals, actual held-page caller counts, observed lifetime
and any already-cached root height. The selected-candidate marker records selection
context; it is not a race-free assertion that another writer has not changed the
shared target meanwhile.

Summarize and package:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/diagnostics/summarize_rawstore_occupancy.py \
  build/reports/delosdb/benchmarks/f08-occupancy-sampled --paired \
  --output build/reports/delosdb/benchmarks/f08-occupancy-sampled/occupancy-summary.json
# Package raw evidence even if the summarizer exits 2 for failed calibration.
tar -czf build/reports/delosdb/benchmarks/f08-occupancy-sampled-evidence.tar.gz \
  -C build/reports/delosdb/benchmarks f08-occupancy-sampled
```

Retain HEAD, working-tree diff, JDK version and the gate results with the archive.
The summary checks all 64 measurement windows, preserves raw reports, compares
median OFF/ON throughput and Derby reference drift, and highlights the largest
individual resource occupancies. A >5% change invalidates architectural inference;
stop and address measurement overhead rather than promote an optimization.

## Decision discipline

For each instance in full-accounting mode use `U = hold_ns / elapsed_ns` and
`occupancy_per_row = hold_ns / inserted_rows`. In sampled mode use the separately
labelled weighted estimates and their between-run dispersion, retaining raw counts.
A sampling estimate above the physical utilization bound signals imprecision; it
is not proof of saturation. Compare 4→8 growth in both outer
occupancy and its disjoint nested components; compare BARE and PK.
`1e9 / occupancy_per_row` is a saturation capacity bound only under the assumption
that this instance services the measured row stream. It is not literal row latency.

`U / occupancy_per_row` algebraically equals measured throughput when both quantities
use the same denominator. Agreement with measured throughput is therefore **not an
independent validation of the model**. Require high utilization plus the predicted
change in nested components and the BARE/PK difference instead.

Heap targets move as pages fill. A page's first-to-last observed span allows a
within-lifetime utilization check, but a single short span or a single acquisition
can trivially appear fully utilized. Treat that as supporting attribution, not a
global ceiling. Inspect acquisition counts and overlapping candidate lifetimes;
prove a common serialized target lane before combining holds from changing pages.
Root and LogToFile instances generally persist throughout the measured interval.

Only calibration-valid evidence that explains roughly 70% or more of the
8-client inverse-throughput budget, with consistent 4→8 inflation and PK overhead,
supports the proposed nested-ownership architecture. If no stable serialized
instance/verified target lane explains the ceiling, stop. Do not implement
writer-affine targets, optimistic descent or concurrent WAL reservation from this
hypothesis. This tranche ends at qualification and runnable campaign commands.


## Ledger-OFF stability investigation (2026-10-10)

The completed BARE/4-client OFF cohort reproduced instability: Heap ranged from
123,637 to 197,833 rows/s (46.1% range/median); Derby ranged from 319,230 to
357,058 (11.3%). Heap GC and safepoint pauses were roughly 7–10 ms in windows
lasting 5–8 seconds, so direct pauses cannot explain the throughput gap.
Compilation remained active; cumulative compiler time does not establish causality.
A separate Java workload consumed several cores during one slow run. Low free
physical memory and increased memory compression accompanied slow windows, but
these observations do not identify an exact cause. Existing swap allocation alone
is not evidence of active swapping.

The optional quiet-entry guard completed one Heap/Derby pair (207,278/351,995
rows/s), then timed out before Heap run 2. This partial cohort does not establish
a stable baseline. The guard requires three consecutive two-second samples with
CPU <=20%, load <=half the available processors, and >=2 GiB free memory. These
are conservative entry conditions, not proof that a whole window remains quiet.
It does not terminate competing processes or change host settings.

Reproduce the OFF diagnostic after other builds and memory-heavy applications
have been paused by their owner; use a fresh report tag:

```bash
. ~/Development/jdk25-env.sh
./gradlew :delosdb-tests:runDelosF08OccupancyCampaign \
  -Pdelosdb.sane=false -Pdelosdb.occupancy.shape=BARE \
  -Pdelosdb.occupancy.enabled=false -Pdelosdb.occupancy.clients=4 \
  -Pdelosdb.occupancy.stabilityDiagnostics=true \
  -Pdelosdb.occupancy.quietGuard=true \
  -Pdelosdb.occupancy.reportTag=stability-quiet-repeat --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 tools/diagnostics/summarize_rawstore_stability.py \
  build/reports/delosdb/benchmarks/f08-occupancy-stability-quiet-repeat/BARE/false \
  --output build/reports/delosdb/benchmarks/f08-occupancy-stability-quiet-repeat/stability-summary.json
```

JVM diagnostics are default-OFF and include asynchronous GC/safepoint/JIT logs,
process CPU and management counters aligned to measurement boundaries. Host
samples are sparse and filtered to that window. Logging and host sampling can
perturb performance: use these to investigate instability, not to qualify sampler
overhead. Once an OFF baseline is stable, repeat the interleaved paired BARE/4
calibration with `quietGuard=true`, `enabled=paired`, and JVM stability diagnostics
OFF. Keep the 5% calibration requirement and stop wider campaigns/optimization
until it passes. RCM-1 and database algorithms remain unchanged.

Evidence: `build/reports/delosdb/benchmarks/f08-occupancy-stability-off/` contains
the complete cohort and aligned summary; `f08-occupancy-stability-quiet-off/`
contains the partial guarded attempt and its guard observations. The stability
evidence archive preserves both and qualification results.
