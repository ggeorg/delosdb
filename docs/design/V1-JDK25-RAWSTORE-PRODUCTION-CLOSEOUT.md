# DelosDB v1 RawStore production closeout

Status: Stage 8.7.1 and Stage 8.7.2 verified; superseded by the verified Stage 8.7.3 final production closeout.

## Purpose

Stages 8.1 through 8.6 established and verified the shared positional I/O, diagnostics, deterministic
failure, heap-segment, bounded native-mirror, and mapped-region experiment boundaries. Stage 8.7 begins
the production closeout before Lucene work. It removes concrete lifecycle, runtime-surface, code
duplication, stale naming, and documentation debt without changing page, WAL, recovery, locking, or
transaction semantics.

Stage 8.7.1 closed lifecycle and API defects. Stage 8.7.2 completes the benchmark-backed page-I/O
representation decision and removes both foreign-memory experiments from the v1 runtime.

## Duplicate active registration

A canonical `file:` or `memory:` database identity may have at most one active RawStore I/O metrics
owner and one active fault injector.

Registration now follows the same deterministic rule in both directories:

```text
no current entry
    -> install weak reference

same live owner
    -> idempotent success

different live owner
    -> reject duplicate active registration

stale weak reference
    -> atomically replace and retry on a race
```

A duplicate active registration never replaces the existing runtime and never removes its terminal
or active evidence.

## Partial-registration rollback

`BaseDataFileFactory` binds database-owned metrics and the disabled fault injector before publishing weak directory registrations. If canonical-name resolution or either
registration fails, the partially bound objects are shut down and any registration owned by that
factory is discarded without publishing a misleading terminal runtime snapshot.

Normal database shutdown still publishes bounded terminal diagnostics and fault evidence.

## Test-only replay manifest

`DelosRawStoreIoFailureReplayManifest` has no production producer or consumer. It exists only to
serialize and verify deterministic failure evidence in the focused Stage 8.3 test.

Stage 8.7.1 therefore moves it from `delosdb-derby-store-api` to `delosdb-tests`. The runtime contract
inventory returns from 105 to 104 types:

```text
101 contracts migrated during Stage 7.3
  3 Stage 8 runtime diagnostics contracts
---
104 runtime store/type contracts
```

The test-only replay manifest remains strict and fully covered, but it is no longer a supported or
accidental runtime API.

## Shared complete-transfer loops

`DirRandomAccessFile` previously duplicated the same complete positional read and write loops for
byte arrays and memory segments. Both front ends now create a `ByteBuffer` view and delegate to one
private read loop and one private write loop.

This keeps EOF, zero-progress, read-only, absolute-position, and closed-on-interrupt behavior in one
human-readable implementation per direction.

## Test-support naming

The engine test patch uses neutral Gradle task and directory names for its package-private RawStore proof bridge:

```text
prepareRawStoreInternalTestSupportSources
compileRawStoreInternalTestSupport
generated/sources/raw-store-internal-test-support
```

The patch remains test-only and is never packaged into a production artifact.

## Removed dead surface

The unused `DelosRawStoreNativeMemory.enabled()` method is removed. Native allocation behavior is
already expressed by `allocate(...)` returning a lease or deterministic heap fallback.

## Stage 8.7.2 page-I/O representation decision

Stage 8.7.2 keeps the direct positional `byte[]` path and removes the heap `MemorySegment` alias and
bounded native mirror from production. The decision is supported by state-equivalent repeated
FileChannel workloads, code-size review, lifecycle review, and the absence of a copy or ownership
benefit.

```text
KEEP_POSITIONAL_BYTE_ARRAY
REMOVE_HEAP_MEMORY_SEGMENT_FROM_V1_RAWSTORE
REMOVE_NATIVE_PAGE_IO_MIRROR_FROM_V1_RAWSTORE
```

The diagnostics snapshot advances to schema version 3 and removes native-memory fields. The old
Stage 8.4/8.5 runtime-focused tests are replaced by one test-only representation decision benchmark.

## Remaining non-goals

Stage 8.7.2 does not yet:

```text
remove the quarantined Phase 8 retained oracle
reshape the remaining diagnostics snapshot beyond the native-field removal
extract all historical Stage 8 gates from the shared analysis script
begin Lucene DP-5 through DP-8
```

## Permanent evidence

```text
:delosdb-tests:runDelosSharedRawStoreIoDiagnosticsTest
:delosdb-tests:runDelosSharedRawStoreIoFaultInjectionTest
:delosdb-tests:runDelosSharedRawStorePageIoRepresentationDecisionTest
delosStorageApiModuleRetirementStaticAnalysis
delosSharedRawStoreProductionCloseoutStaticAnalysis
```
