# DelosDB documentation map

This documentation set is organized around stable architecture and research material, not per-overlay
state notes.  The goal is to keep DelosDB readable as an education- and research-friendly modern
RDBMS while preserving enough source traceability to the inherited Derby code.

## Primary architecture documents

| Document | Purpose |
|---|---|
| `architecture/delosdb-modern-rdbms-roadmap.md` | Project direction, phases, and decision rules. |
| `architecture/delosdb-modern-rdbms-model.md` | The teachable modern RDBMS model DelosDB will implement and observe. |
| `architecture/delosdb-engine-source-map.md` | How the inherited Derby engine maps to SQL compiler, optimizer, execution, catalog, types, transaction, and runtime services. |
| `architecture/delosdb-storage-source-map.md` | Current storage layout: storage API, inherited Derby storage, native MVCC, and bridge role. |
| `research/rdbms-reference-architectures.md` | Lessons taken from PostgreSQL, Apache Calcite, and HerdDB. |

## Supporting documents kept as reference material

| Document | Purpose |
|---|---|
| `BUILDING.md` | Build instructions. |
| `DERBY-COMPATIBILITY.md` | Compatibility notes inherited from Derby. |
| `MVCC-MISSION.md` | MVCC mission and project direction. |
| `sql-extensions.md` | SQL extension notes. |
| `storage/mvcc-design.md` | Native MVCC design notes. |
| `storage/derby-store-access-boundary.md` | Derby store access boundary notes. |
| `storage/mvcc-access-method-registration-path.md` | MVCC access-method registration path. |
| `storage/mvcc-derby-qualifier-boundary.md` | Qualifier and predicate boundary notes. |
| `storage/mvcc-storage-derby-common-ground.md` | Common ground between inherited Derby storage and native MVCC. |

## Documentation policy

Per-overlay state files, temporary analysis notes, and obsolete cleanup notes should not accumulate as
long-term documentation.  When a topic becomes stable, consolidate it into one of the primary
architecture documents above.  Keep deeper subsystem notes only when they remain useful as reference
material.
