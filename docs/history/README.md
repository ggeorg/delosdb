# DelosDB historical engineering records

This directory preserves completed phase closeouts and superseded roadmaps for
provenance. Historical records explain how DelosDB reached the current design;
they do not define current runtime behavior.

Current authority is, in order:

1. the root `README.md`;
2. `docs/PRODUCT-STRATEGY.md`;
3. `docs/ARCHITECTURE.md`;
4. `docs/DERBY-COMPATIBILITY.md`;
5. current protocol documents in `docs/`;
6. current source and executable tests.

## Retained records

| Document | Historical purpose |
|---|---|
| `PHASE-6-CLOSEOUT.md` | build, module, dependency, and engineering closeout before the concurrent-commit phase |
| `PHASE-7-CLOSEOUT.md` | concurrent commit, durability, maintenance, backup, isolation, and production-hardening closeout |
| `STORAGE-ROADMAP-PHASES-A-J.md` | superseded storage-development sequence retained as design provenance |
| `ENGINE-DEPTH-ROADMAP.md` | superseded comparison-driven engine-depth plan |
| `RELEASE-READINESS-EARLY-PLAN.md` | early release-readiness plan superseded by the v1.0 program |

Intermediate Phase 7 slice documents were consolidated into current protocol
authorities:

```text
MVCC-DURABILITY-PROTOCOL.md
MVCC-GROUP-COMMIT.md
MVCC-MAINTENANCE.md
MVCC-BACKUP-COORDINATION.md
```

A historical document may describe an implementation that was later replaced
or hardened. Use it to understand why a change was made, not to infer current
behavior or supported configuration.
