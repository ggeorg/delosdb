# DelosDB Roadmap

DelosDB is a Java 21, Derby-compatible database kernel. The project keeps the
SQL/JDBC surface familiar while replacing risky inherited internals through
small executable proofs.

North star:

```text
A Java developer can build and test serious database internals — storage,
indexing, optimizer costing, recovery, and MVCC — against a real SQL engine,
without losing Derby compatibility by accident.
```

## Current rule

The A44--A52 MVCC semantic-correctness sprint is closed. Choose the next lane
explicitly before starting new work.

Do not start new provider families, research-platform subsystems, new SQL
surfaces, or a global default-store flip as a side effect of MVCC cleanup.

Workspace metadata is not a cleanup target. Local workspace ZIP snapshots may
contain `.git/`, `.gradle`, and `.idea`. Cleanup scripts must never delete those
paths.

## Closed major lane: ASM bytecode

ASM is now the production generated-bytecode backend. The old Derby bytecode
backend and old classfile writer are quarantined. The permanent proof is:

```bash
./gradlew generatedBytecodeAsmJvm21Proof
```

Do not reopen ASM work unless a concrete build/test failure points back to
bytecode generation.

## Finished provider seams

### CostModelProvider v2

Status: finished seam.

Active native path:

```text
RAMTransaction.openStoreCost()
  -> StoreCostControllerBridge
  -> CostModelProviderResolver
  -> CostModelProvider
```

Proven implementations:

```text
factory id 0 -> heap CostModelProvider
factory id 1 -> btree CostModelProvider
```

Known boundary: Derby's `StoreCostResult` can propagate only total cost and
estimated row count. Richer path/cost vectors are future optimizer work.

### IndexProvider v2

Status: finished abstraction proof.

Proven implementations:

```text
index btree  -> Derby-compatible SQL-backed index provider
index memory -> provider-owned runtime operations proof
```

`btree` remains the only normal SQL-creatable index provider. `memory` is visible
in the registry and has its own runtime proof, but `CREATE INDEX ... USING
memory` is intentionally rejected until a real Derby executor/storage bridge
exists for non-B-tree physical indexes.

## Frozen shallow seams

These remain deliberately shallow while MVCC is the active lane:

- `FunctionProvider`: built-in DelosDB function surface only.
- `TypeProvider`: metadata-only Derby type visibility.
- new provider families: not opened.


## Closed lane: legacy Derby store Phase B closeout

The real Derby store module boundary is closed when
`verifyLegacyDerbyStoreB9Consolidation` is green.

Result:

```text
delosdb-storage-derby
  org.apache.derby.iapi.store.*
  org.apache.derby.impl.store.*
```

The inherited Derby store is now a real compiled storage module. Derby package
names, class names, disk format, `modules.properties` boot wiring, and default
heap behavior remain compatible. Runtime packaging remains compatibility-first:
`derby.jar` still includes the inherited Derby store runtime classes, and
existing users do not need to manually add a separate storage jar yet.

B9 adds the final non-runtime consolidation guard: stale B5/B6 transition proof
text is cleaned, Gradle task/helper names are checked for duplicates, legacy
store source ownership is checked for duplication, and local stale workspace
artifacts are isolated to `scripts/cleanup-overlay-b9-stale-files.sh`.

The extraction does not flip `delos_mvcc` to default and does not make MVCC
depend on Derby store internals. The detailed closeout notes live in
`docs/legacy-derby-store-phase-b-closeout.md`.

## Closed lane: MVCC semantic correctness sprint

The MVCC path has moved beyond source mapping and simple provider smokes. It now
has an isolated storage module, provider SPI, page-backed durability proofs,
provider-owned index proofs, SQL opt-in smokes, a guarded default-provider
candidate path, and the A44--A52 correctness gates.

Current rule:

```text
No property:
  CREATE TABLE ...        -> normal legacy Derby-compatible heap path

-Ddelosdb.storage.defaultProvider=delos_mvcc:
  bare CREATE TABLE ...   -> guarded delos_mvcc candidate path

CREATE TABLE ... USING delos_mvcc:
  explicit experimental MVCC path
```

The global default store is not flipped.

Important current gates:

```bash
./gradlew mvccDefaultProviderCandidateMatrix
./gradlew mvccTransactionLockOrderProof
./gradlew mvccKernelReviewCloseoutProof
./gradlew mvccHistoryPrunedSafetyProof
./gradlew mvccVacuumWatermarkProof
./gradlew mvccCommandSequenceProof
./gradlew mvccStatementSnapshotVisibilityProof
./gradlew mvccSqlStatementBoundarySmoke
./gradlew mvccTransactionOutcomeLogProof
./gradlew mvccUnresolvedOutcomeRecoveryProof
./gradlew mvccCapturedVisibilitySnapshotProof
./gradlew mvccSqlCompatibilityCandidate
```

Closed A44--A52 plan:

```text
A44 — missing-history / prune-safety
A45 — vacuum watermark integration
A46 — command sequence model
A47 — statement snapshot visibility
A48 — SQL statement-boundary smoke
A49 — durable transaction outcome log
A50 — unresolved outcome recovery
A51 — captured visibility-state snapshot
A52 — MVCC SQL compatibility candidate matrix
```

The detailed mission and four-engine comparison live in:

```text
docs/MVCC-MISSION.md
```


## Closed lane: storage Phase C table-access/routing slice

The C19--C25 storage slice is closed by `verifyStoragePhaseC26Consolidation`.

Result:

```text
C19 — review gaps closed
C20 — real store-neutral table-access capability contracts
C21 — MVCC equality SELECT through DelosFilterableTableAccess
C22 — Derby heap compile-time honesty proof
C23 — MVCC UPDATE/DELETE through scan-produced row identities
C24 — Derby JavaCC / QueryTreeNode classifier proof
C25 — first regex route deletion
C26 — consolidation and cleanup boundary
```

The first regex route deleted is only:

```text
SELECT * FROM table WHERE column = literal
```

That route now goes through Derby JavaCC / QueryTreeNode classification and then
through the table-access contract. Remaining regex routes are temporary fallbacks.
Do not delete another regex route until an equivalent QueryTreeNode classifier is
green for that exact statement type.

Honest boundary: MVCC still enters through `EmbedStatement ->
VersionedStorageSqlBridge.tryExecute(...)`. This work cleans routing and creates
a real table-access execution boundary, but it does not yet move MVCC behind
Derby's binder/compiler/executor metadata path.

Detailed closeout notes live in:

```text
docs/storage-phase-c19-c25-closeout.md
```

## Closed lane: storage Phase C guarantee and regex-retirement slice

The C27--C36 storage slice is closed by `verifyStoragePhaseC37RouteRetirementCloseout`.

Result:

```text
C27 — guarantee honesty, separate from structural capabilities
C28 — caller-side NOT_EQUAL leftover-predicate evaluation
C29 — JavaCC / QueryTreeNode range SELECT classifier
C30 — delete standalone > range regex
C31 — JavaCC / QueryTreeNode INSERT VALUES classifier
C32 — delete INSERT VALUES regex
C33 — JavaCC / QueryTreeNode DELETE equality classifier
C34 — delete DELETE equality regex
C35 — JavaCC / QueryTreeNode UPDATE equality classifier
C36 — delete UPDATE equality regex
C37 — route-retirement closeout and remaining-regex inventory
```

Detailed closeout notes live in:

```text
docs/storage-phase-c27-c36-closeout.md
```


## Completed lane: storage Phase F native Derby execution integration

Phase F is the clean-design correction after the bridge-retirement slice.  C37
is the last bridge-expansion closeout.  New MVCC SQL work must move behind Derby
catalog metadata, `ResultSetFactory`, and generated activation execution instead
of expanding `VersionedStorageSqlBridge`.

Important correction:

```text
F1 grammar work is already done.
sqlgrammar.jj has storageProviderClause().
CreateTableNode and TableDescriptor already carry storageProviderName in memory.
```

Completed sequence:

```text
F0  — freeze bridge expansion and clean generated smoke DB artifacts
F1a — parser-owned provider syntax confirmation smoke only
F2.1 landed — persist storageProviderName across restart via nullable SYSTABLES.STORAGEPROVIDER
F2.2 landed — add DelosTableScanProviderLookup for ResultSetFactory metadata lookup preparation
F3.1 landed — provider-aware ResultSetFactory branch proof
F3.2 landed — DelosTableScanResultSet skeleton
F4 landed — native MVCC SELECT equality through generated activation/result-set path
F5 landed — native INSERT through provider-owned table access
F6 landed — native DELETE equality through provider-owned row identity delete
F7 landed — native UPDATE equality through provider-owned row identity update
F8 landed — bridge bypass for native mode, compatibility bridge explicit only
Phase F build streamline landed — consolidated Phase F Gradle wiring and retired per-step report guards
G0 landed — native Qualifier[][] conjunction cleanup before range predicates
G1 landed — native remaining range predicates: >, >=, <, <=
G2 landed — native BETWEEN predicate coverage
Next: G3 — native SELECT * full scan
```

F3/F4 are the hard frontier: generated activations currently emit
`getTableScanResultSet(...)`; the preferred proof keeps that bytecode shape and
branches inside `GenericResultSetFactory` using the passed `tableName` plus the
activation's `LanguageConnectionContext` to resolve `TableDescriptor` and its
`storageProviderName`.

Detailed plan lives in:

```text
docs/storage-phase-f-native-integration-plan.md
docs/storage-phase-f-consolidation.md
```

## Active lane: storage Phase G native predicate coverage

Phase G starts after F8. The bridge is no longer the place for production MVCC
SQL expansion. New SQL coverage should extend the native Derby execution path
proved in Phase F.

Current verified cleanup slice:

```text
G0 — native Qualifier[][] conjunction cleanup
```

G0 keeps OR groups unsupported, but accepts multiple ANDed equality terms and
applies remaining equality filters after the first pushed equality candidate.

Current verified predicate slice:

```text
G1 — native remaining range predicates: >, >=, <, <=
```

G1 extends `DelosTableScanResultSet` predicate translation from equality to
range qualifiers and keeps the same constraints: no new bridge route, no regex
route, no generated bytecode shape change.

Current verified BETWEEN slice:

```text
G2 — native BETWEEN predicate coverage
```

G2 proves Derby `BETWEEN` reaches the native Delos scan path.  Derby lowers
`BETWEEN` into ordinary range qualifiers, so the native implementation reuses
the G1 range predicate machinery rather than adding a new bridge route or regex
path.

Next verified slice:

```text
G3 — native SELECT * full scan
```

## Research-friendly constraint

DelosDB should be friendly to database-systems research and university teaching,
but that must not become a second product before the engine is correct.

Allowed now:

```text
small proof-level traces;
readable assertion messages;
inspectable internal value objects;
proof output that explains a decision already being tested.
```

Still parked until a separate post-A52 decision:

```text
new SQL EXPLAIN surfaces;
teaching profiles;
research property families;
deterministic scheduler;
fault-injection framework;
labs/ directory;
artifact packaging;
benchmark harness.
```

## Post-A52 decision point

A44--A52 are green. The next work should be selected deliberately from one lane:

1. row locks / `SELECT FOR UPDATE`;
2. version-aware indexes;
3. additional property-gated default-provider expansion;
4. performance / benchmark sanity for the guarded MVCC candidate.

Until that choice is made, keep Derby heap as default and keep `delos_mvcc`
guarded by explicit syntax or `delosdb.storage.defaultProvider=delos_mvcc`.

## Future direction

After the next lane is selected and proven, DelosDB can consider deeper lanes:

```text
row locks / SELECT FOR UPDATE;
semi-consistent reads for UPDATE/DELETE conflict checks;
wait/retry/abort conflict decisions;
serializable isolation;
version-aware indexes;
tombstone/side-index experiments;
adaptive version storage;
optimizer path vocabulary;
benchmark/research artifact support;
formal teaching labs.
```

These are future lanes, not near-term gates.

## Documentation cleanup

Active MVCC planning belongs in `docs/MVCC-MISSION.md`.

`docs/postgres-class-storage-concurrency.md` is retained as historical source
trail for the early PostgreSQL-class storage/concurrency campaign.

`docs/postgresql-deep-gap-map.md` is stale and should be removed; it belongs to
the pre-MVCC source-mapping phase and contains obsolete guidance.

## Book rule

Do not update the book as part of MVCC roadmap cleanup. Book changes require a
separate pass with source trails and chapter verification status updates.

## Build verification cleanup

Closed C/F text-token report guards are retired from active build wiring after
Phase F closeout. Permanent storage boundary guards, SPI truth-map checks,
closed C7 regression smoke, and Phase F native execution smokes remain active.
