# MVCC Buffer Replacement Policy Audit

This is an audit artifact, not a behavior change.

The goal is to make the current MVCC buffer replacement algorithm explicit before any future policy abstraction, benchmark lane, or adaptive replacement experiment.

## Non-goals

* No Java runtime behavior change.
* No default replacement policy change.
* No cache format change.
* No page flush ordering change.
* No checkpoint/recovery ordering change.
* No heap raw-store cache dependency.
* No shared buffer manager extraction is authorized by this audit.
* No JMH benchmark is required in S0.

## Current policy

The current MVCC decoded-page cache uses `MvccBufferReplacementPolicy` with `MvccPageCache`.

The policy is deterministic and simple:

1. `MvccPageCache` stores cached pages in a `LinkedHashMap` configured for access order.
2. Replacement scans from the least-recently-used end of that access-order map.
3. A page with a positive pin count is protected.
4. A dirty page is protected until flush makes it clean.
5. The first clean and unpinned page is the victim.
6. If every page is pinned or dirty, the policy reports no victim and the cache may temporarily exceed its nominal bound.

This means the current policy is best described as:

```text
access-order LRU candidate selection
+ pin protection
+ dirty-page protection
+ no-victim reporting
```

It is intentionally not CLOCK, CLOCK-Pro, CAR, ARC, or an InnoDB-style production buffer pool yet.

## Existing proof points

`MvccBufferReplacementPolicyTest` proves:

* least-recently-used clean page eviction,
* dirty-page protection until flush,
* no-victim reporting when every page is protected.

`MvccPageCachePinDirtyTest` proves pin/dirty lifecycle behavior.

`MvccBufferManagerPhase2Test` proves the WAL-before-flush guard and grouped page-volume force behavior.

`runDelosMvccLifecycleProofs` wires the buffer replacement, phase-2 flush, and pin/dirty tests into the MVCC lifecycle proof group.

## Algorithmic risks

The current policy is a safe first-generation policy, but it is not yet a database-grade buffer manager algorithm.

Known risks and future audit targets:

* Linear replacement scans may become expensive under dirty or pinned pressure.
* All-dirty or all-pinned states can retain more pages than the nominal cache size.
* Dirty-page protection is correct for safety but may need checkpoint-aware flush pressure.
* The policy does not distinguish row pages, ordered-index pages, overflow pages, free-space-map pages, visibility-map pages, or recovery metadata pages.
* The policy has no recency/frequency split like CLOCK-Pro or CAR-style algorithms.
* The policy has no benchmark-backed evidence for mixed read/write workloads.
* The policy has no JFR surface yet for eviction pressure, dirty protection, pin leaks, or no-victim events.

## Reference models

HerdDB is the closest Java-engine reference for this slice. HerdDB ClockProPolicy and ClockAdaptiveReplacement patterns are reference models for future policy comparison, not dependencies to import.

InnoDB is the reference model for production buffer-pool lifecycle: dirty-page pressure, flush ordering, checkpoint interaction, and page-class residency.

PostgreSQL is a reference model for buffer clock-sweep style replacement and how buffer lifecycle interacts with checkpoints and vacuum pressure.

H2 is a reference model for compact Java page-cache discipline and store-level inspection.

JDK 25 is relevant through JFR observability and JMH validation lanes, not through production behavior changes in this audit.

## Candidate future policies

Future implementation slices may compare:

* current access-order LRU with pin/dirty protection,
* CLOCK-style second-chance replacement,
* CLOCK-Pro-style hot/cold classification,
* CAR-style adaptive recency/frequency behavior,
* page-class-aware weighting for row/index/overflow/FSM/VM/recovery pages,
* checkpoint-aware dirty-age scoring.

None of those candidates is selected by this audit.

## Guardrails for the next implementation slice

A future buffer-policy implementation must keep these constraints:

* Default behavior remains unchanged until tests and benchmarks prove the replacement.
* Pinned pages are never evicted.
* Dirty pages are not evicted without a safe flush path.
* WAL-before-flush remains enforced by `MvccBufferFlushCoordinator`.
* Replacement policy abstraction must be testable without changing storage format.
* Any benchmark lane stays outside S0 unless it becomes a deterministic static gate.
* Shared heap/MVCC buffer services are not authorized until heap has a compatible proof point.

## Recommended next steps

1. Keep this D1 audit green.
2. Add a policy abstraction only after this audit is accepted.
3. Add JMH storage benchmark adapters after the abstraction is testable.
4. Add JFR events for eviction/no-victim/flush pressure after the event vocabulary is stable.
5. Revisit shared cache services only after heap diagnostics and compatibility gates prove a safe common boundary.
