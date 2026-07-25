# V1 RawStore MVCC authority cutover

## Status

```text
STAGE 5.1 VERIFIED
STAGE 5.4 PRODUCTION RETIREMENT IMPLEMENTED / PENDING USER VERIFICATION
```

RawStore is the only production authority for `delos_mvcc`.

The former `delosdb.mvcc.rawStoreVerticalSlice.enabled` property remains only as an ignored
compatibility key for old launch scripts and tests. It no longer selects a second format and setting
it to `false` cannot restore the retained runtime.

## Sole production boot path

One booted `MvccConglomerateFactory` owns:

```text
one MvccRawStoreRuntime
RawStore-backed table descriptors
RawStore transaction lifecycle participants
RawStore maintenance and diagnostics
```

The factory does not construct or register:

```text
MvccDatabaseRuntime
DelosStorageStore
MvccInheritedStore
MvccDatabaseCommitCoordinator
external MVCC WAL/checkpoint/recovery/page-volume state
retained table-state diagnostics
```

The retained controller/runtime classes are excluded from the production bridge artifact.

## Retained-state guard

A directory database can still contain files created by the retired Phase 8 format. DelosDB does not
interpret, migrate, dual-write, delete, or recover those files.

Before constructing `MvccRawStoreRuntime`, the factory checks `<database>/delos_mvcc/` read-only with
`NOFOLLOW_LINKS`. Any regular file, symbolic link, unknown entry, unreadable state, or failed traversal
rejects boot before maintenance or diagnostics registration. Empty compatibility directories remain
harmless.

There is no property or fallback path that opens retained state.

## Read routing

```text
RawStore descriptor present
    -> return RawStore-backed conglomerate

RawStore descriptor absent
    -> fail closed as retired external format
```

The former cross-authority maximum-container-ID scan is removed because a production boot has only one
physical authority.

## Runtime and verification boundary

`derby.jar` publishes `DerbyMvccAccessMethodProvider` through the neutral
`ExternalAccessMethodProvider` service.

`delosdb-storage-mvcc.jar` and `delosdb-storage-io.jar` are outside root assembly, normal runtime,
and SQL/DRDA test classpaths. The retired `DelosStorageProviderFactory` service entry is excluded.
The archived implementation is built and tested only through:

```text
delosMvccRetainedRuntimeRetirementStaticAnalysis
```

Normal `check` and S0 use the RawStore-backed implementation.

## Permanent evidence

```text
docs/design/V1-RAWSTORE-MVCC-RETAINED-RUNTIME-RETIREMENT.md
:delosdb-tests:runDelosMvccRawStoreAuthorityCutoverTest
:delosdb-tests:runDelosMvccSqlIntegrationTest
delosMvccRawStoreAuthorityCutoverStaticAnalysis
delosMvccRetainedRuntimeRetirementStaticAnalysis
verifyDelosRuntimeStorageProviders
```

## Deferred work

This cut does not yet delete every archived Phase 8 source file or retire the temporary Gradle module
itself. Source/module deletion follows after the explicit legacy oracle is no longer needed for
historical differential analysis. It does not begin Stage 6 memory completion, final module collapse,
JDK 25 modernization, or Lucene work.
