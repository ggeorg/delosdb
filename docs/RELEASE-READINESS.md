# DelosDB Release Readiness

## Selected next phase

The closed algorithm/value-add implementation cycle is followed by **Release-readiness hardening**.
This phase is deliberately selected before deeper correctness or performance work because the current
tree already has broad algorithm proofs, external validation adapters, and deterministic differential
coverage. The immediate risk is accidental promotion of advisory tooling into normal build or runtime
paths, not the absence of another engine feature.

## Slice A1 — external adapter boundary proof

Status: active proof slice.

`delosReleaseReadinessExternalAdapterBoundary` is the aggregate release gate for external validation
isolation. It verifies all of the following together:

* External validation adapters remain opt-in.
* JMH, jcstress, and SQLancer assets stay below `benchmarks/`, outside normal module source sets.
* Normal Java sources do not import external validation APIs.
* Normal Gradle build files do not declare JMH, jcstress, SQLancer, Calcite, HerdDB, or MapDB dependencies.
* Normal module/build wiring does not depend on external adapter tasks.
* `s0CloseoutVerification` does not execute external validation adapters.
* The release report is generated at `build/reports/delosdb/release-readiness-external-adapter-boundary.txt`.

The gate itself is safe for S0 because it performs deterministic static inspection only. It never runs
JMH, jcstress, SQLancer, soak workloads, or externally supplied commands.

## Scope boundary

This slice makes **No engine behavior changes**. It does not alter Derby compatibility, heap or MVCC
storage behavior, SQL/JDBC/DRDA behavior, optimizer decisions, durable formats, recovery, buffering,
purge, or benchmark results. It does not add runtime dependencies and does not promote any external
adapter into a normal source set.

## Deliberate follow-up order

After A1 is green, continue release-readiness work in small slices:

1. Gradle deprecation inventory and Gradle 10 classification.
2. Generated-report ownership, reproducibility, and stale-report audit.
3. Dead-code candidate classification without automatic deletion.
4. Documentation consolidation and closed-roadmap index.

Deeper correctness and performance phases remain valid later choices. Shared-service extraction remains
blocked unless provider-neutral readiness reports prove that an execution algorithm—not only a read-only
reporting seam—is ready to move.
