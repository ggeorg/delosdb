# DelosDB project status

## Current state

The following repository programs are complete and accepted:

```text
RawStore convergence
JDK 25 generated-class modernization
storage/module authority consolidation
repository-integrity cleanup
permanent-gate consolidation
```

The current architecture is:

```text
SQL/compiler/JDBC/DRDA
        -> Derby-compatible engine
        -> one Derby RawStore persistence authority
        -> heap or DelosDB MVCC access method
```

`delos_mvcc` owns transaction identity, commit sequence, snapshots, visibility, conflict detection,
version chains, and retention semantics. It does not own a parallel persistence runtime.

Generated activation classes use `ClassFileJava` and the JDK 25 Class-File API. External ASM,
backend selection, and fallback generation are absent.

## Repository-integrity closeout

The permanent baseline is version 23:

```text
Dead private production methods:          0
Dead private production fields:           0
Exact duplicate production groups:       44
Methods in duplicate groups:             105
Estimated exact duplicate lines:         960
Production methods >= 100 lines:          442
Production complexity >= 20:              168
Production classes >= 1000 lines:          136
Production silent empty catches:            102
Production generic catches:                 410
Compiler authority violations:                0
Compiler compromise candidates:               0
Java parse errors:                             0
```

Remaining duplicate, catch, and structural findings are classified inherited or compatibility debt.
The baseline is monotonic: new debt fails; accepted debt may decrease.

## Permanent verification

Normal S0 has seven direct authorities:

```text
delosModuleDependencyBoundaryStaticAnalysis
delosV1ModuleArchitectureStaticAnalysis
delosGeneratedClassStaticAnalysis
delosRepositoryIntegrityStaticAnalysis
verifyDelosRuntimeStorageProviders
delosJdk25ClassFileBytecodeVerifier
:delosdb-tests:runDelosSecurityTruthTest
```

Run them through:

```bash
./gradlew s0CloseoutVerification --console=plain
```

Historical stage tasks and exact-text gates were retired. Comments, Markdown, roadmap wording, and
report prose do not participate in pass/fail.

## Current phase

The current implementation phase is **Phase 11 — Product Completeness**.

Phase 10.1 is complete. The immutable schema-version-1 selected-plan model now exposes:

- deterministic statement and node identity;
- logical/physical operations;
- heap vs `delos_mvcc` storage mode and selected access path;
- join strategy, row estimates, and cost estimates;
- store/residual/requalification/filter predicate placement;
- explicit ORDER BY and selected-index ordering;
- forced/cost-selected access and join decisions, non-covering index fetch, required sort/filter;
- core `VALUES`, aggregate, distinct, set-operation, row-limit, derived-table, INSERT, UPDATE, and
  DELETE shapes, including store-level `DISTINCT_SCAN` when duplicate elimination is scan-owned;
- bounded `GENERIC` / `UNCLASSIFIED_RESULT_SET` fallback for genuinely unknown result-set nodes.

Successful non-DML statements can have stable statement metadata with no result-set plan. Parse/bind/
optimize rejection is not converted into a synthetic plan: Derby's SQLState remains the authoritative
rejection result.

Phase 10.2 is complete. `EXPLAIN <statement>` returns one row with deterministic `PLAN_TEXT` and
`PLAN_JSON` CLOBs rendered from the prepared statement's same immutable model. The target is
bound/optimized normally but is not executed; compile-time SQLStates are preserved. Schema-version-1
field ordering is frozen as the public text/JSON contract. Prepared parameters, statement-cache
recompile determinism, heap/MVCC/join/predicate/`DISTINCT_SCAN` rendering, embedded/DRDA byte parity,
and large CLOB delivery beyond the DRDA external-data threshold have permanent coverage.

Phase 10.3 is complete. Query-only `EXPLAIN ANALYZE` executes the selected query once and correlates
bounded runtime counters and heap/`delos_mvcc` scan evidence to stable plan-node ids through generated
result-set identity. Derby's existing timing authority is enabled only for ANALYZE; schema version 3 added
nullable authoritative per-operator `actualRows`; schema version 4 added deterministic actual-vs-estimated
classification; schema version 5 added exact MVCC read-path/version-traversal summaries where existing
counters prove them; and schema version 6 added the exact snapshot sequence already owned by each MVCC
scan. Plain MVCC table scans remain explicitly `NOT_MEASURED` for version traversal rather than paying for
new hot-loop instrumentation. Timing and snapshot sequence are the only execution-specific fields normalized
for embedded/DRDA parity.

Phase 10.4 is complete. One Gradle-owned SQL demonstration serves as the shared trace for public docs and
teaching material: parse/bind/optimize, stable plan capture, JDK 25 activation generation, real result-set
execution, MVCC scan/snapshot ownership, immutable runtime evidence, and text/JSON CLOB rendering. The
Phase 10 closeout added no second plan, execution authority, optimizer behavior, storage behavior, or
profiling path.

Phase 11 now owns remaining v1 product-completeness work: explicit heap/MVCC and embedded/DRDA parity,
large-value and streaming lifecycle, bounded resources and physical-space reuse, DRDA lifecycle, and
corresponding executable documentation.

Repository-integrity stages and Phase 10 are closed and should not be reopened as endless cleanup or
diagnostic-expansion programs.
