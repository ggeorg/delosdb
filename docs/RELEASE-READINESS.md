# DelosDB release-readiness planning record

> **Status:** Historical. This document records an earlier phase selection and is not the current roadmap. The former `delosReleaseReadinessExternalAdapterBoundary` aggregate task is no longer registered. External validation isolation is now maintained by `delosPerformanceConcurrencyValidationStaticAnalysis`, the opt-in external validation tasks, and the standalone `benchmarks/jmh` build.

## Historical phase selection

The closed algorithm/value-add implementation cycle selected **release-readiness hardening** before deeper correctness or performance work because the tree already had broad algorithm proofs, external validation adapters, and deterministic differential coverage. The immediate risk was accidental promotion of advisory tooling into normal build or runtime paths, not the absence of another engine feature.

## Historical Slice A1 — external adapter boundary proof

Status: completed and superseded by the current validation ownership described above.

`delosReleaseReadinessExternalAdapterBoundary` was the aggregate release gate for external validation
isolation. It verified all of the following together:

* External validation adapters remain opt-in.
* JMH, jcstress, and SQLancer assets stay below `benchmarks/`, outside normal module source sets.
* Normal Java sources do not import external validation APIs.
* Normal Gradle build files do not declare JMH, jcstress, SQLancer, Calcite, HerdDB, or MapDB dependencies.
* Normal module/build wiring does not depend on external adapter tasks.
* `s0CloseoutVerification` does not execute external validation adapters.
* The release report was generated at `build/reports/delosdb/release-readiness-external-adapter-boundary.txt`.

The gate was safe for S0 because it performed deterministic static inspection only. It did not run
JMH, jcstress, SQLancer, soak workloads, or externally supplied commands.

## Scope boundary

This slice made **no engine behavior changes**. It did not alter Derby compatibility, heap or MVCC
storage behavior, SQL/JDBC/DRDA behavior, optimizer decisions, durable formats, recovery, buffering,
purge, or benchmark results. It did not add runtime dependencies or promote any external adapter into
a normal source set.

## Historical follow-up order

The planned follow-up order was:

1. Gradle deprecation inventory and Gradle 10 classification.
2. Generated-report ownership, reproducibility, and stale-report audit.
3. Dead-code candidate classification without automatic deletion.
4. Documentation consolidation and closed-roadmap index.

Those items were subsequently covered by later build, report, dead-code, and documentation work. Shared-service extraction remains blocked unless provider-neutral readiness reports prove that an execution algorithm—not only a read-only reporting seam—is ready to move.
