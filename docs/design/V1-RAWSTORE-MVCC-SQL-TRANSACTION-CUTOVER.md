# V1 RawStore MVCC SQL transaction-authority proof cutover

## Status

```text
IMPLEMENTED / PENDING USER VERIFICATION
```

This is the third Stage 5 retirement slice. It retargets the permanent SQL multi-table and mixed
heap/MVCC transaction proof from the retained Phase 8 database-decision protocol to the inherited
RawStore transaction used by the converged format.

No production class is removed in this slice. The retained implementation remains available only for
still-unretargeted legacy suites while its replacement evidence is accumulated.

## Retired proof dependency

The three SQL methods previously described their outcome through a separate database decision:

```text
testTwoMvccTablesCommitThroughOneDatabaseDecision
testTwoMvccTableRollbackRemainsAtomic
testMixedHeapAndMultipleMvccWritesUseOneRawStoreDecision
```

Those names and their focused task implied that SQL atomicity still depended on an external MVCC
commit authority.

The replacement proof explicitly enables:

```text
delosdb.mvcc.rawStoreVerticalSlice.enabled=true
```

and uses only RawStore-backed `delos_mvcc` tables.

## Replacement SQL proof

The focused lane proves three SQL-facing contracts:

```text
two MVCC tables commit
    -> one inherited RawStore transaction
    -> both tables visible after shutdown and reopen

two MVCC tables roll back
    -> one inherited RawStore undo boundary
    -> both pre-transaction values remain visible

one heap table plus two MVCC tables
    -> one inherited RawStore transaction
    -> all three commit together
    -> later mixed mutations roll back together
    -> all committed values survive shutdown and reopen
```

Each database is cleanly shut down before reopen. After the final shutdown the proof asserts that no
regular retained MVCC state exists below `delos_mvcc/inherited-store`.

## Focused task

The permanent focused task is:

```text
:delosdb-tests:runDelosMvccRawStoreSqlTransactionCutoverTest
```

The retired database-decision compatibility alias has been removed.

## Authority invariant

```text
one JDBC transaction
    -> one inherited RawStore transaction
    -> one RawStore commit or rollback outcome
    -> no retained database-decision journal
```

This slice changes proof authority, not SQL semantics.

## Permanent evidence

```text
docs/design/V1-RAWSTORE-MVCC-SQL-TRANSACTION-CUTOVER.md
:delosdb-tests:runDelosMvccRawStoreSqlTransactionCutoverTest
delosMvccRawStoreSqlTransactionCutoverStaticAnalysis
```

The static gate requires:

```text
explicit RawStore-format activation in all three methods
new RawStore transaction/undo method names
clean shutdown before reopen
committed and rolled-back SQL assertions across every participant
zero retained inherited-store files
focused task registration and compatibility alias
absence of the three retained database-decision method names
Stage 5.2 verified and Stage 5.3 roadmap/design evidence
```

## Remaining Stage 5 work

This slice does not yet:

```text
remove the retained database commit coordinator
remove decision-retention production classes or public contracts
retarget all retained lifecycle, checkpoint, backup, and failure suites
make RawStore the default delos_mvcc format
remove the retained runtime
retire storage modules
```

Those removals require their own replacement proofs and absence gates.
