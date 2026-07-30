# DelosDB repository integrity cleanup

Status: STAGE 1 IMPLEMENTED / PENDING VERIFICATION

## Purpose

Before adding the Phase 10.1 stable plan model, DelosDB performs a full-source
cleanup and no-compromise campaign. The campaign must reduce dead code,
duplication, complexity, exception-handling debt, and architecture drift
without changing SQL, transaction, storage, generated-code, JDBC, or DRDA
semantics.

Repository Integrity Stage 1 is evidence-only. It parses the complete Java
source tree with the public `com.sun.source` javac tree API and writes
classification candidates. A candidate is not automatically dead or wrong:
reflection hooks, serialization callbacks, generated parsers, JDBC overloads,
protocol code, and compatibility surfaces require review before removal.

## Permanent tasks

```text
delosRepositoryIntegrityInventory
delosRepositoryIntegrityStaticAnalysis
```

The inventory writes reports under:

```text
build/reports/delosdb/repository-integrity/inventory/
```

The static task fails on Java parse errors, generated-class authority
violations, missing evidence, or increases above the checked-in debt baseline.
Candidate debt may decrease without updating the baseline; increases fail.

## Stage 1 baseline

The initial AST inventory reports:

```text
Java files:                              3303
Production Java files:                   2070
Declared types:                          4014
Declared methods:                       45713
Declared fields:                        21329
Java parse errors:                          0

Dead private production methods:           16 candidates
Dead private production fields:            71 candidates
Exact production duplicate groups:         55
Methods in duplicate groups:              137
Estimated duplicate production lines:    1184
Production methods >= 100 lines:           447
Production methods complexity >= 20:       169
Production classes >= 1000 lines:          140
Production empty catches:                  250
Production generic catches:                473
Production @SuppressWarnings occurrences:   40
Production quality markers:                816
```

These values are a monotonic baseline, not a target-state endorsement.

## Generated compiler no-compromise result

Stage 1 proves:

```text
ClassFileJava is the sole modules.properties registration
no external ASM import remains
no external ASM dependency remains
no ASM JPMS edge remains
only ClassFileJava and DelosJdk25ClassFileVerifier import java.lang.classfile
SQL compiler nodes do not import java.lang.classfile
```

One high-priority classification item remains:

```text
COMPILER-AUTHORITY-001
```

`BaseMonitor` still contains generic system/application `derby.module.*`
override plumbing guarded by the inherited `true || SanityManager.DEBUG`
condition. Stage 2 must prove that this path cannot replace `JavaFactory`, or
pin generated-class authority outside generic override resolution. The Stage 1
gate allows exactly one such candidate and rejects any increase.

## Candidate reports

```text
dead-private-method-candidates.tsv
dead-private-field-candidates.tsv
duplicate-production-method-groups.tsv
production-method-outliers.tsv
production-class-size-outliers.tsv
catch-inventory.tsv
quality-marker-inventory.tsv
compiler-authority-integrity.txt
repository-integrity-report.txt
```

## Cleanup sequence

### Stage 2 — high-confidence dead code

Classify private production candidates against reflection, serialization,
generated-code, SQL routine, and compatibility use. Delete only proven dead
members. Every implementation overlay must be net-negative in handwritten
production Java.

### Stage 3 — DelosDB-owned duplication

Consolidate duplicated validation and test-support code introduced by DelosDB
before modifying inherited JDBC, DRDA, parser, or compatibility boilerplate.

### Stage 4 — quality and structure

Reduce empty/generic catches, oversized methods, deep nesting, suppressions,
and stale markers where correctness and compatibility permit.

### Stage 5 — compiler no-compromise closeout

Resolve `COMPILER-AUTHORITY-001`, review all 43 `MethodBuilder` operations, and
confirm primitive categories, category-two stack values, inferred field owners,
arrays, branches, exception attributes, deterministic generation, and
class-loading lifecycle.

### Stage 6 — final consolidation

Replace the Stage 1 candidate baseline with reduced permanent budgets and keep
new-debt prohibition in S0.

Only after this campaign closes does DelosDB begin Phase 10.1 stable plan
modelling.

## Non-goals

Stage 1 does not:

```text
delete source
change public or internal APIs
change SQL semantics or SQLStates
change generated activation behavior
change module ownership
change storage or MVCC behavior
add a third-party analysis dependency
introduce a second compiler or planner abstraction
```
