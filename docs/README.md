# DelosDB documentation map

This documentation set is organized around stable architecture and research material, not per-overlay
state notes. The goal is to keep DelosDB readable as an education- and research-friendly modern
RDBMS while preserving enough source traceability to the inherited Derby code.

## Primary architecture documents

| Document | Purpose |
|---|---|
| `architecture/delosdb-modern-rdbms-roadmap.md` | Project direction, phases, status, and decision rules. |
| `architecture/delosdb-modern-rdbms-model.md` | The teachable modern RDBMS model DelosDB is restoring as visible engine model vocabulary, with trace and diagnostics as separate observation layers. |
| `architecture/delosdb-contract-ownership-map.md` | Phase 23 ownership classification for contract/API surfaces. |
| `architecture/delosdb-contract-boundary-audit.md` | Phase 23 boundary decision review: no immediate source move is justified. |
| `architecture/delosdb-package-naming-strategy.md` | Rules for package ownership, inherited Derby traceability, and DelosDB-owned package names. |
| `architecture/delosdb-engine-source-map.md` | How the inherited Derby engine maps to SQL compiler, optimizer, execution, catalog, types, transaction, and runtime services. |
| `architecture/delosdb-storage-source-map.md` | Current storage layout: storage API, inherited Derby storage, native MVCC, and bridge role. |
| `architecture/delosdb-mvcc-observation-matrix.md` | Phase 24 MVCC observation status, non-claims, and closeout matrix. |
| `research/rdbms-reference-architectures.md` | Lessons taken from PostgreSQL, Apache Calcite, and HerdDB. |

## Supporting documents kept as reference material

| Document | Purpose |
|---|---|
| `BUILDING.md` | Build instructions and verification gates. |
| `DERBY-COMPATIBILITY.md` | Compatibility policy inherited from Derby and guarded DelosDB opt-in behavior. |
| `sql-extensions.md` | Current SQL extension surface. |
| `storage/derby-store-access-boundary.md` | Derby store/access boundary notes. |
| `storage/mvcc-access-method-registration-path.md` | MVCC access-method registration path. |
| `storage/mvcc-derby-qualifier-boundary.md` | MVCC qualifier and predicate boundary notes. |
| `storage/mvcc-static-analysis-hardening.md` | MVCC hardening work and deferred residuals. |

## Documentation policy

Per-overlay state files, temporary analysis notes, and obsolete cleanup notes should not accumulate as
long-term documentation. When a topic becomes stable, consolidate it into one of the primary
architecture documents above. Keep deeper subsystem notes only when they remain useful as reference
material.

Do not list documents here unless they exist in the repository. Removed or consolidated notes should
be represented by the surviving architecture or storage document that now owns the decision.
