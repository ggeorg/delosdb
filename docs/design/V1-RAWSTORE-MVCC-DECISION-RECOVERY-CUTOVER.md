# V1 RawStore MVCC decision and recovery proof cutover

## Status

```text
IMPLEMENTED / PENDING USER VERIFICATION
```

This is the second Stage 5 retirement slice. It retargets the permanent mixed heap/MVCC power-loss
proof from the retained Phase 8 decision journal to the inherited RawStore commit and recovery
boundary used by the converged format.

The retained implementation is not deleted in this slice. Its remaining fault, checkpoint, recovery,
and operational suites stay available only in explicit legacy mode until each responsibility has a
RawStore replacement proof.

## Retired proof mechanism

The former test named `MvccRawStoreDecisionWalCrashTest` did not exercise the RawStore-backed MVCC
format. It booted the retained format, installed `MvccFailurePointRegistry` through reflection,
copied the Derby log directory, restored that snapshot after process halt, and inspected retained
`database-decisions/*.decision` files.

That proof depended on two transaction authorities:

```text
Derby RawStore log
retained MVCC database-decision journal
```

It is no longer valid evidence for the Stage 5 architecture.

The permanent lane now contains none of these mechanisms:

```text
MvccFailurePointRegistry
BEFORE_DERBY_RAW_STORE_COMMIT
reflection-based fault installation
RawStore log-directory snapshot or restoration
retained database-decisions inspection
retained MVCC sidecar recovery
```

## Replacement proof

The retargeted proof explicitly enables:

```text
delosdb.mvcc.rawStoreVerticalSlice.enabled=true
```

It creates one heap table and one RawStore-backed MVCC table, mutates both in the same JDBC
transaction, and halts the child JVM at both accepted RawStore MVCC boundaries:

```text
after-stamp-before-raw-commit
    -> process status 91
    -> inherited RawStore recovery rolls back heap and MVCC mutations

after-raw-commit-before-publication
    -> process status 92
    -> inherited RawStore recovery exposes both committed mutations
```

No test-owned log restoration is performed. Reopening the database normally invokes the inherited
Derby recovery pass.

## Authority invariant

The proof protects this exact outcome:

```text
one mixed heap/MVCC JDBC transaction
    -> one inherited RawStore commit record
    -> one inherited RawStore recovery decision
    -> matching heap and MVCC visibility after reopen
```

The database is also scanned after recovery to prove that RawStore mode created no regular file below
`delos_mvcc/`. Therefore no retained MVCC WAL, transaction-outcome journal, decision marker,
checkpoint, page volume, or sidecar participated in the proof.

## Permanent evidence

```text
docs/design/V1-RAWSTORE-MVCC-DECISION-RECOVERY-CUTOVER.md
:delosdb-tests:runDelosMvccRawStoreDecisionWalCrashTest
delosMvccRawStoreDecisionRecoveryCutoverStaticAnalysis
```

The static gate requires:

```text
both inherited RawStore crash boundaries
mixed heap and RawStore-backed MVCC mutations
normal reopen without copied-log restoration
matching rollback or commit visibility
absence of retained failure registry, decision paths, and reflective installation
absence of regular files below delos_mvcc/
Stage 5 roadmap, architecture, and proof records
```

## Remaining Stage 5 work

This slice does not yet:

```text
make RawStore the default delos_mvcc format
remove the retained runtime or its production classes
retarget every retained checkpoint, backup, recovery, and failure-injection suite
remove transaction-outcome journals or decision-retention APIs
remove external MVCC WAL, checkpoints, recovery files, page volumes, or sidecars
remove the retained database commit coordinator
retire storage modules
```

Those responsibilities are removed only after their corresponding RawStore replacement suites and
absence gates are green.
