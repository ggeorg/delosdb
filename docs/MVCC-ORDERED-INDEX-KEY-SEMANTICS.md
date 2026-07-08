# MVCC Ordered-Index Key Semantics Audit

This is an audit artifact, not a behavior change.

DelosDB ordered MVCC index pages are already normal current-committed row-id authority for covered equality and range paths. This document records the semantic boundary that must be kept visible before deeper key-codec or index-layout changes. The goal is to make Derby SQL key behavior explicit without replacing Derby optimizer authority, weakening MVCC visibility, or resurrecting candidate indexes as normal SQL authority.

No storage format change is made by this slice. No Java runtime path-selection change is made by this slice. No candidate-index authority is restored by this slice. No heap behavior is changed by this slice. No Calcite, HerdDB, or MapDB dependency is added by this slice.

## Current ownership

| Area | Current owner | Classification | Notes |
| --- | --- | --- | --- |
| Derby SQL comparison behavior | Derby SQL / type system | DERBY_COMPATIBILITY_ALGORITHM | DelosDB must preserve Derby-observable comparison behavior. |
| Typed ordered-index key envelope | `DelosStorageOrderedIndexKey` | TYPED_KEY_BOUNDARY | Encodes Derby store values into `DOK1` typed envelopes for ordered MVCC lookup. |
| Bridge key production | `MvccInheritedIndexMaintenance` | BRIDGE_KEY_PRODUCTION | Produces typed ordered-index entries from committed MVCC rows. |
| Durable ordered index sorting/range | `MvccOrderedIndexPageStore` | DURABLE_SORT_AND_RANGE | Sorts and range-filters typed envelopes without importing Derby SQL value classes. |
| Candidate index parity | `MvccCandidateIndex` plus diagnostics | DIAGNOSTIC_ONLY_ALGORITHM | Candidate paths remain diagnostic/parity only. |
| Existing SQL proofs | `MvccSqlTypedOrderedIndexKeyTest` and ordered-index tests | VALIDATION_ALGORITHM | Current tests prove numeric typed range behavior and snapshot shortcut exclusion. |

## Semantic inventory

### NULL

SQL NULL through a Derby value holder is represented by the typed envelope kind `DOK1|N|`. That is the supported ordered-index NULL shape.

Raw Java `null` cells in a `StoreDataValue[]` are not a valid ordered-index key authority shape. The recent inherited transaction fixture failure was a stale fixture issue, not permission to make raw null object cells normal index keys. Future NULL proofs should use real Derby null value holders, not arbitrary `null` cell references.

Classification: `KNOWN_SEMANTIC_GAP` until focused SQL NULL ordered-index tests exist.

### multi-column keys

The current ordered-index sidecar stores entries by `column + typed key + row id`. That is enough for current single-column equality/range shortcut proofs, but it is not yet a physical composite-key envelope.

Composite Derby index semantics need an explicit tuple-key codec before DelosDB claims composite ordered-index authority.

Classification: `KNOWN_SEMANTIC_GAP`.

### duplicate keys

Duplicate keys are represented by repeated typed key entries with row-id tie-breaking. This supports non-unique equality/range row-id lookup, but duplicate-key behavior must remain tied to visibility filtering and Derby uniqueness enforcement.

Classification: `DURABLE_SORT_AND_RANGE` with validation coverage required before deeper uniqueness work.

### unique constraints

Unique constraint behavior is still Derby/SQL authority plus existing DelosDB uniqueness gates. The ordered MVCC index sidecar must not become an independent uniqueness oracle unless a specific compatibility gate proves Derby-equivalent unique and nullable-key semantics.

Classification: `DO_NOT_TOUCH_WITHOUT_COMPAT_GATE`.

### ASC/DESC

The current durable ordered-index key envelope does not encode physical sort direction. Range lookup uses typed key comparison and row-id reads; final SQL ordering remains Derby-observable behavior.

ASC/DESC-aware physical index traversal needs a separate audit and proof before it becomes an access-path authority decision.

Classification: `KNOWN_SEMANTIC_GAP`.

### collation-sensitive comparison

Text payload comparison currently uses the typed envelope's text payload ordering. Derby collation-sensitive comparison must not be assumed unless the key envelope records the relevant collation/locale semantics or a compatibility gate proves equivalence for the supported database mode.

Classification: `KNOWN_SEMANTIC_GAP`.

### typed Derby values

The current `DelosStorageOrderedIndexKey` boundary supports numeric, decimal, floating, temporal, text, and NULL envelope categories. Existing SQL tests prove integer, bigint, decimal, and text-prefix behavior for ordered-index range lookup.

Classification: `TYPED_KEY_BOUNDARY`.

### large keys

`MvccOrderedIndexPageStore.Entry` normalizes oversized keys to an oversized hash/prefix representation so one entry can fit on a page. That protects the sidecar from oversized slot failures, but range semantics over hash-normalized large values must not be treated as Derby-equivalent without a dedicated proof.

Classification: `KNOWN_SEMANTIC_GAP` for range semantics, `DURABLE_SORT_AND_RANGE` for the current fit-on-page guard.

### overflow-backed key attributes

The ordered-index key is derived from materialized `StoreDataValue` values. It does not yet use an attribute-overflow descriptor as the key itself. Overflow-backed values need a focused proof that materialization, key derivation, ordered-index entry storage, and row-id lookup remain stable across reopen/vacuum/backup.

Classification: `KNOWN_SEMANTIC_GAP`.

### range boundaries

Numeric range boundaries are already covered by typed key tests. Text, temporal, NULL-inclusive, exclusive bounds, composite bounds, collation-sensitive bounds, and large-key bounds need focused follow-up proofs before DelosDB claims full ordered-index key semantics.

Classification: `VALIDATION_ALGORITHM` for current numeric proofs and `KNOWN_SEMANTIC_GAP` for the remaining boundaries.

## Reference models

Calcite is a reference model for explicit traits, metadata, and path explanation. HerdDB is a reference model for explicit index-operation objects and small Java database lifecycle checks. MapDB is a reference model for typed serializers, packed/delta key material, and binary-searchable key arrays. These are reference models only; this audit does not add them as dependencies.

## Required follow-up proofs

1. `delosdb-mvcc-ordered-index-null-key-proof-overlay.zip`
   - SQL NULL through Derby value holders.
   - No raw Java null cell authority.
   - Candidate diagnostic parity remains quarantined.

2. `delosdb-mvcc-ordered-index-composite-range-proof-overlay.zip`
   - Composite tuple-key envelope design.
   - Equality and range determinism.
   - Visibility filtering remains correct.

3. `delosdb-mvcc-ordered-index-collation-direction-audit-overlay.zip`
   - Collation-sensitive text comparison.
   - ASC/DESC physical traversal boundary.
   - Derby compatibility gate before behavior changes.

4. `delosdb-mvcc-ordered-index-large-key-proof-overlay.zip`
   - Oversized key equality and range restrictions.
   - Overflow-backed attribute materialization.
   - Reopen/backup/vacuum stability.

## NULL key proof checkpoint

`delosdb-mvcc-ordered-index-null-key-proof-overlay.zip` adds the first semantic
proof after this audit.  The proof keeps `DelosStorageOrderedIndexKey` as the
bridge boundary, preserves Derby SQL NULL semantics, and verifies that the
durable ordered-index page store can sort, lookup, and range-scan the typed
`DOK1|N|` NULL envelope.

The SQL bridge proof deliberately checks that a table containing SQL NULL values
can commit, reopen, remain consistent, and still use ordered pages for supported
non-NULL typed range lookups.  It does not make `IS NULL` a new ordered-index
shortcut and does not change optimizer authority.
