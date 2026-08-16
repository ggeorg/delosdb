# DelosDB project status

## Current state

DelosDB currently includes these established foundations:

```text
RawStore convergence
JDK 25 generated-class modernization
storage/module authority consolidation
repository-integrity cleanup
permanent-verification consolidation
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

## Repository integrity

The current frozen repository-integrity baseline is:

```text
Dead private production methods:            0
Dead private production fields:             0
Exact duplicate production groups:         43
Methods in duplicate groups:               103
Estimated exact duplicate lines:           953
Production methods >= 100 lines:            442
Production complexity >= 20:                168
Production classes >= 1000 lines:           136
Production generic catches:                 410
Compiler authority violations:                0
Compiler compromise candidates:               0
Java parse errors:                             0
```

Remaining duplicate, catch, and structural findings are classified inherited or compatibility debt.
The baseline is monotonic: new debt fails; accepted debt may decrease.

## Permanent verification

The normal permanent verification suite has seven direct authorities:

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

Permanent verification is based on executable or structural evidence. Comments, Markdown, planning
wording, and report prose do not participate in pass/fail.

## Pre-1.0 product focus

The readable-engine foundation is implemented: one stable selected-plan model, deterministic `EXPLAIN`,
bounded query-only `EXPLAIN ANALYZE`, operator timing and cardinality evidence, estimate comparison, MVCC
read-path and snapshot diagnostics, and one executable end-to-end trace.

### Performance evidence

Current performance evidence comes from two distinct benchmark environments that must not be merged into
one general performance claim.

**Embedded/JDBC:** Delos Heap, Delos MVCC, Apache Derby, H2, and native SQLite through Xerial JDBC were
compared on focused read workloads. Resident HOT and DISJOINT point reads showed Delos MVCC and Delos Heap
in the same performance class with the current snapshot-registry implementation. RANDOM remains sensitive to current-row cache
capacity/associativity. H2/SQLite retain an advantage on simple embedded indexed reads, so indexed execution
cost remains an area for further evidence-driven optimization.

**Server/container:** Delos Heap and Delos MVCC over DRDA were compared with PostgreSQL and MariaDB for
prepared disjoint primary-key reads at READ COMMITTED. The measured matrix used 10,000 rows, 1/2/4/8/16
clients, transaction widths 1 and 10, four balanced runs, and no retryable conflicts. At 16 clients the four
engines converged within only a few percent in the measured cases. This is a narrow simple indexed point-SELECT
result; it does not establish general performance for joins, aggregates, sorting, writes, mixed OLTP,
durability-heavy workloads, or optimizer quality.

### Remaining pre-1.0 work

Remaining work includes broader SQL/workload validation, JVM/JIT indexed-read profiling, RANDOM current-row
cache investigation, write and mixed-reader/writer performance, optimizer work where evidence requires it,
and completion of product capabilities that are still intentionally unsupported. True MVCC `SERIALIZABLE`
remains a pre-1.0 requirement; the current `0A000` rejection is an implementation boundary, not a permanent
product decision.

