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

The permanent baseline is version 19:

```text
Dead private production methods:          0
Dead private production fields:           0
Exact duplicate production groups:       48
Methods in duplicate groups:             115
Estimated exact duplicate lines:        1009
Production methods >= 100 lines:          443
Production complexity >= 20:              169
Production classes >= 1000 lines:          137
Production silent empty catches:            102
Production generic catches:                 434
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

The current implementation phase is **Phase 10.2 — EXPLAIN**.

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

Phase 10.2 must render deterministic text and machine-readable EXPLAIN from this one model. It must
not introduce another optimizer tree, execution authority, or compiler IR.

Repository-integrity stages are closed and should not be reopened as an endless cleanup program.
