# V1 RawStore-backed MVCC ordered indexes

## Status

```text
IMPLEMENTED BEHIND THE RAWSTORE MVCC OPT-IN
```

The opt-in RawStore-backed `delos_mvcc` format uses Derby's inherited B-tree access method for
version-aware ordered candidates. The index has no filesystem path, page volume, WAL, checkpoint,
recovery pass, cache, or commit decision outside Derby access and RawStore.

## Ownership and authority

A table owns:

```text
metadata and stable-row directory container
version container
ordered-index generation directory
one Derby B-tree per SQL-orderable base-table column
```

The generation directory is an ordinary RawStore container. Each mapping row records:

```text
base-table column id -> Derby B-tree conglomerate id
```

The B-trees are ordinary Derby access-method conglomerates backed by RawStore. Their creation, page
splits, inserts, deletes, undo, recovery, and drop share the same access transaction and RawStore
commit as the base MVCC table.

The ordered index is never row authority. It returns stable `MvccRowId` candidates only. Every
candidate is reread through the authoritative MVCC version chain, and the full SQL qualifier set is
applied again before a row is returned.

## Physical B-tree layout

Each non-tombstone version contributes one entry to every orderable-column B-tree:

```text
typed Derby key
MvccRowId
MvccVersionId
MvccRowLocation
```

The fields are ordered as one immutable B-tree key. The typed SQL key is first, enabling bounded
partial-key scans. Stable row/version identity distinguishes historical candidates, and
`MvccRowLocation` is last because Derby B2I requires the base-row location in the final field.

The B-tree intentionally does not duplicate transaction or commit-sequence visibility fields. A
candidate's authoritative creator/begin/end state is read from the base version chain before the row is
returned. An uncommitted or historical entry may therefore remain physically discoverable without ever
becoming visible to the wrong transaction or snapshot.

BLOB, CLOB, LONG VARCHAR, LONG VARCHAR FOR BIT DATA, XML, and user-defined values are not duplicated
into B-trees. Qualifiers on those columns use the authoritative stable-row directory and version-chain
scan. This also prevents stream-backed values from being consumed a second time.

## Mutation and commit publication

INSERT and UPDATE insert only the new version's B-tree rows during statement execution. DELETE appends
a tombstone base version and creates no new key entries.

Before the one inherited RawStore commit, the access-method participant stamps only authoritative base
state:

```text
new base-version begin sequence
predecessor base-version end sequence
database committed high-water
```

No B-tree row is rewritten during commit. RawStore undo removes an inserted immutable candidate on
statement failure, savepoint rollback, transaction rollback, or crash before commit. The base version's
creator/begin/end fields remain the sole visibility authority.

Historical entries remain until vacuum so an older snapshot can find an old key while a newer snapshot
finds its replacement or observes a committed delete.

## Equality, range, and uniqueness lookup

Safe single-column equality and range predicates open the corresponding B-tree with typed partial-key
bounds:

```text
equality:          [key, key]
closed/open range: Derby GE/GT start and stop operators
```

Candidate row IDs are de-duplicated in B-tree order and then processed through:

```text
B-tree candidate (MvccRowId, MvccVersionId)
    -> authoritative MVCC version-chain lookup
    -> snapshot visibility
    -> full RowUtil qualifier evaluation
    -> returned row
```

Native unique constraints probe the first constrained column's exact key range and then compare every
constraint column against authoritative visible rows. Unsupported qualifier shapes and qualifiers on
non-orderable columns fall back to the full stable-row directory scan.

## Generation replacement and compatibility

A history-removing vacuum builds a transaction-private directory and B-tree set from the retained
authoritative versions, publishes the new generation through the table control row, and drops the old
B-trees and directory in the same access transaction.

Tables created before the ordered-index field remain readable through the base scan. A legacy flat
candidate generation is also recognized as non-authoritative compatibility state. The first later write
transactionally builds a Derby B-tree generation before applying the mutation. There is no boot-time
filesystem migration and no dual durability path.

Temporary MVCC conglomerates receive their access-layer identity after the factory returns; B-tree
creation is therefore deferred until the access manager has registered the base conglomerate. Persistent
and temporary B-trees use the same inherited access and RawStore lifecycle.

## Recovery and executable proof

The executable proof covers:

```text
one inherited Derby B-tree per orderable column
bounded equality and range lookup
transaction-local INSERT/UPDATE visibility
committed UPDATE and DELETE visibility
historical snapshots
savepoint rollback
clean reopen
halt before and after RawStore commit
legacy generation compatibility rebuild
transactional vacuum generation replacement
file and jdbc:derby:memory: databases
```

Focused runtime gate:

```text
:delosdb-tests:runDelosMvccRawStoreOrderedIndexTest
```

Permanent architecture gate:

```text
delosMvccRawStoreOrderedIndexStaticAnalysis
```

## Current limits

This milestone does not add:

```text
SQL CREATE INDEX / DROP INDEX lifecycle for arbitrary user-defined MVCC indexes
predicate or range-gap locking
physical removal of historical entries outside vacuum
final optimizer statistics and costing for the internal candidate B-trees
```

Those capabilities must build on the retained Derby B-tree and RawStore authorities. They must not
restore an external index store or independent durability lane.

## Native uniqueness and locking

Inline primary-key and unique constraints are persisted and enforced by the MVCC access method. The
B-tree narrows candidates, but authoritative version-chain visibility and typed key comparison decide
conflicts. Logical schema, row, and typed unique-key locks remain the semantic conflict authority;
Derby's inherited B-tree locking supplies physical access coordination only.

```text
docs/design/V1-RAWSTORE-MVCC-UNIQUE-CONSTRAINTS.md
docs/design/V1-RAWSTORE-MVCC-UNIQUE-LIFECYCLE.md
docs/design/V1-RAWSTORE-MVCC-LOGICAL-LOCKING.md
docs/design/V1-RAWSTORE-MVCC-PHYSICAL-LOCKING.md
```
