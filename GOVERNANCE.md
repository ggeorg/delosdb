# Governance

DelosDB is currently a maintainer-led fork.

## Decision principles

1. Derby-compatible behavior before novelty.
2. Working proofs before new architecture.
3. One behavior boundary per milestone.
4. Source citations before book claims.
5. Finished lanes before new provider families.
6. Benchmarks before performance claims.
7. Clear attribution to Apache Derby.

## Current project rule

The active lane is MVCC semantic correctness. Do not open a new provider family,
new research-platform subsystem, or global default-store flip while A44--A52 are
incomplete.

Closed/finished areas:

- ASM generated-bytecode backend is the production path.
- `CostModelProvider` v2 is routed through Derby's native store-cost seam.
- `IndexProvider` v2 has B-tree SQL-backed and memory provider-owned proofs.

Guarded active area:

- `delos_mvcc` versioned storage remains explicit or property-gated.
- Heap remains the default storage path.
- MVCC promotion depends on the proof ladder in `docs/MVCC-MISSION.md`.

## Research-facing rule

Research/university friendliness is allowed only when it supports the current
engine proof. Proof-level traces, readable assertions, and inspectable internal
objects are acceptable. Separate labs, profiles, schedulers, fault-injection
frameworks, artifact packaging, and new SQL explain surfaces wait until the MVCC
correctness ladder is closed.

## Release rule

No release should be cut unless these gates are green:

```bash
./gradlew fullVerification
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

No release should present `delos_mvcc` as the default storage path until the MVCC
semantic, durability, vacuum, compatibility, and crash/recovery gates have been
explicitly promoted.
