# Phase 7.7 — Transaction Sidecar Force Batching

## Purpose

Phase 7.4 reduced main-table page forcing to one force per transaction, but the
Phase 7.6 benchmark still showed page-state publication dominating same-table
commit time.

The remaining row-count amplification was outside the page-volume metric:

```text
one row:
    one forced row-directory append
    one forced free-space-map rewrite

eight rows:
    eight forced row-directory appends
    eight forced free-space-map rewrites
```

A local executable comparison measured:

```text
before Phase 7.7:
    one row   other sidecar forces = 15
    eight rows other sidecar forces = 29

after Phase 7.7:
    one row   other sidecar forces = 15
    eight rows other sidecar forces = 15
```

The 14 removed force calls are the seven additional row-directory forces and
seven additional free-space-map forces from the eight-row transaction.

## Transaction sidecar boundary

The committed transaction page path now uses:

```text
prepared mutation payload and outcome fence
    one main-table page mutation context
        stage every version page
        update the in-memory free-space map after each page mutation
        rewrite and force the free-space-map sidecar once
        write and force all dirty main-table pages once
    update the in-memory row directory for every committed version
    append every row-directory head in one forced transaction batch
    rebuild the visibility map once
```

The transaction still publishes the same logical row heads and free-space
values. Only the number of force boundaries changes.

## Free-space-map authority

The free-space map remains a rebuildable sidecar. During a transaction batch,
all updated page capacities remain visible in the in-memory map, so later row
placements in the same transaction use current free-space information.

The durable sidecar is rewritten once immediately before the main page-volume
force, preserving the existing relative ordering. If publication fails after
the outcome fence, strict recovery rematerializes the transaction and open-time
free-space reconciliation rebuilds the sidecar from page state.

Single-record page append, page rewrite, vacuum, and recovery paths retain their
existing immediate free-space-map publication behavior.

## Row-directory authority

Version pages remain the row payload and version-chain authority. The
row-directory sidecar is an append-only head index that is reconciled from page
state on open.

All row heads from one committed transaction are now encoded into one append
and one force. The file format is unchanged: it remains one version-1 record per
line.

Open-time replay now uses the shared durable-line parser. A final line without a
line terminator is treated as a torn append tail, discarded, and the complete
prefix is rewritten before page-state reconciliation. A malformed complete line
still fails loudly.

This gives these crash rules:

```text
complete row-directory transaction batch
    recover every head normally

torn final transaction batch
    discard only the incomplete final line
    retain the complete prefix
    reconcile missing or stale heads from durable pages

malformed complete historical record
    reject open as corruption
```

## Observability

The commit JFR event already records:

```text
otherSidecarForceCount
directoryForceCount
```

The concurrent-commit benchmark now prints both values in its human and console
output as:

```text
sidecar=<forces per commit>
directory=<directory forces per commit>
```

The focused proof compares fresh one-row and eight-row commits and requires the
same other-sidecar force count. It also appends a torn row-directory batch tail,
reopens the table, and verifies complete row visibility and consistency.

## Force contract

For ordinary inline-row commits, the established headline contract remains:

```text
2 transaction-status forces
1 transaction-outcome force
1 WAL force
2 page-volume forces
```

Phase 7.7 additionally removes row-count growth from the measured other-sidecar
force count:

```text
one-row sidecar force count = eight-row sidecar force count
```

## Remaining serialized work

This slice does not remove the constant per-commit sidecar work:

```text
prepared payload journal force
free-space-map force
main-table row-directory force
visibility-map atomic rewrite
cross-subsystem recovery-record appends
checkpoint lifecycle and metadata rewrites
ordered-index publication
```

The next benchmark should determine the new page-state persistence time. The
next implementation target should then be chosen between constant recovery
record/checkpoint force batching and incremental ordered-index maintenance.

## Out of scope

This slice does not:

```text
change row-directory or free-space-map formats
make either sidecar authoritative over version pages
change the transaction outcome fence
change WAL or page-volume ordering
change main-table or ordered-index page-force counts
change commit-sequence allocation
allow concurrent same-table physical publication
add cross-transaction group commit
change checkpoint frequency
change vacuum, purge, or backup coordination
change SQL, JDBC, DRDA, or catalog behavior
```
