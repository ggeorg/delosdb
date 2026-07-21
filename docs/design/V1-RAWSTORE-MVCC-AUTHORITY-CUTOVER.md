# V1 RawStore MVCC authority cutover

## Status

```text
IMPLEMENTED / PENDING USER VERIFICATION
```

This is the first Stage 5 retirement slice. It removes the retained Phase 8 persistence runtime from
any database boot that explicitly selects the RawStore-backed `delos_mvcc` format.

The retained format is not deleted in this slice. A database booted without the RawStore opt-in may
still open it as the differential and recovery oracle while the remaining Stage 5 tests are retargeted.
The two persistence systems are no longer allowed to coexist inside one booted access-method factory.

## Authority rule

When:

```text
delosdb.mvcc.rawStoreVerticalSlice.enabled=true
```

one `MvccConglomerateFactory` owns only:

```text
one MvccRawStoreRuntime
RawStore-backed table descriptors
RawStore transaction lifecycle participants
RawStore maintenance and diagnostics
```

It does not construct or register:

```text
MvccDatabaseRuntime
DelosStorageStore
MvccInheritedStore
MvccDatabaseCommitCoordinator
Phase 8 page/WAL/checkpoint/recovery objects
Phase 8 table-state diagnostics
```

The factory returns immediately after the RawStore runtime and its non-owning diagnostics registration
are established. The retained runtime is constructed only in the explicit non-RawStore branch.

## Retained-state guard

A directory database may contain durable Phase 8 files from an earlier boot. RawStore authority does
not attempt to interpret, migrate, dual-write, delete, or recover those files.

Before the RawStore runtime starts, the factory performs one read-only transitional guard over:

```text
<database>/delos_mvcc/
```

If any non-directory entry or symbolic link exists below that retained provider directory, RawStore authority fails closed
with an explicit instruction to boot with the RawStore property disabled to access the retained
format.

The guard:

```text
runs before MvccRawStoreRuntime construction
opens no DelosStorageStore
starts no maintenance worker
registers no RawStore diagnostics runtime
mutates no retained file
```

Empty compatibility directories left by earlier convergence builds are harmless and do not prevent a
RawStore-only boot.

## Read routing

A persisted RawStore table is still identified only by its RawStore control-row magic.

Read dispatch is now:

```text
RawStore descriptor present
    -> require RawStore mode
    -> return RawStore-backed conglomerate

RawStore descriptor absent + RawStore mode selected
    -> reject retained external-format table

RawStore descriptor absent + RawStore mode not selected
    -> use retained Phase 8 runtime
```

There is no RawStore-mode fallback to the retained runtime.

## Conglomerate identity

The former compatibility scan for the maximum persisted Phase 8 conglomerate ID is removed.
RawStore mode cannot coexist with retained Phase 8 state, so no cross-authority ID collision needs to
be avoided inside one boot.

RawStore table creation keeps only the factory-local monotonic reservation needed for multiple
RawStore MVCC tables created during one boot. RawStore remains the physical container-ID authority.

## Failure and reopen behavior

A rejected cutover:

```text
leaves retained files byte-for-byte owned by the retained format
creates no RawStore MVCC table
starts no RawStore maintenance runtime
publishes no RawStore diagnostics registration
```

Rebooting with the RawStore property disabled continues to open the retained table and its data.

A clean RawStore database proves the opposite boundary: RawStore maintenance diagnostics are
available, while retained database-storage diagnostics fail because no `MvccDatabaseRuntime` exists.

## Permanent evidence

```text
docs/design/V1-RAWSTORE-MVCC-AUTHORITY-CUTOVER.md
:delosdb-tests:runDelosMvccRawStoreAuthorityCutoverTest
delosMvccRawStoreAuthorityCutoverStaticAnalysis
```

The focused executable proof covers:

```text
clean file-database RawStore operation
absence of retained sidecar files
absence of MvccDatabaseRuntime
presence of the RawStore runtime
fail-closed retained-state detection before RawStore runtime registration
non-mutation of retained files
successful retained-format reopen when RawStore mode is disabled
```

## Remaining Stage 5 work

This slice does not yet:

```text
make RawStore the default delos_mvcc format
delete the retained Phase 8 implementation
retarget all Phase 8 fault/recovery tests
remove transaction-outcome journals or decision retention
remove external MVCC WAL, checkpoint, recovery, page volumes, or sidecars
remove compatibility diagnostics APIs
retire storage modules
```

Those responsibilities are removed only after their corresponding RawStore tests and gates are
retargeted and green.
