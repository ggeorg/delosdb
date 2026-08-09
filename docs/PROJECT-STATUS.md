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

The current implementation phase is **Phase 10.1 — Stable Plan Model**.

The plan model should expose the optimizer's existing decision through a deterministic, readable
representation containing stable statement/node identity, operation, access path, storage mode,
join/index strategy, estimates, predicates, and rejection/fallback reasons.

It must not become:

- a second parser or binder;
- a second optimizer;
- a second execution plan authority;
- another generated-class intermediate representation;
- a runtime compiler backend selector;
- an excuse to bypass `JavaFactory` / `ClassBuilder` / `MethodBuilder`.

Phase 10.1 begins from the current clean baseline. Its first foundation slice retains a bounded,
immutable representation of Derby's already-selected optimized plan on the prepared statement, with
deterministic node identity, operation, storage/access-path, join-strategy, and estimate fields.
Predicate placement, ordering, and fallback/rejection reasons remain the next 10.1 slice.

Repository-integrity stages are closed and should not be reopened as an endless cleanup program.
