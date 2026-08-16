# DelosDB v1 shared RawStore I/O fault injection and replay

Status: VERIFIED.

## Decision

The implementation installs one deterministic, database-scoped fault seam around the shared RawStore page-I/O
boundary established by Stages 8.1 and 8.2. Heap and RawStore-backed MVCC therefore encounter the
same injected physical failure. The seam is disabled by default and has no SQL, connection attribute,
system property, service-provider, or public application control surface.

The implementation remains inside the inherited RawStore module:

```text
BaseDataFileFactory
    -> owns one DelosRawStoreIoFaultInjector
    -> binds it to the same canonical file: or memory: identity as I/O diagnostics
    -> registers only a weak same-package test control reference
    -> publishes a bounded immutable terminal snapshot at shutdown

RAFContainer / RAFContainer4
    -> reach named points before and after page read
    -> reach named points before and after page write
    -> distinguish content-only and metadata force points
    -> reach points around interrupt-driven channel reopen
```

No second page authority, storage backend, WAL, recovery engine, or MVCC-only fault path is created.

## Registry and occurrence semantics

Registry version 1 defines:

```text
BEFORE_PAGE_READ
AFTER_PAGE_READ
BEFORE_PAGE_WRITE
AFTER_PAGE_WRITE
BEFORE_FORCE_CONTENT
AFTER_FORCE_CONTENT
BEFORE_FORCE_METADATA
AFTER_FORCE_METADATA
BEFORE_CHANNEL_REOPEN
AFTER_CHANNEL_REOPEN
```

A schedule matches an exact point and positive occurrence. Occurrences begin when a schedule is
installed into an already active database-scoped injector. A seeded schedule selects one point from
an explicit candidate list with `SplittableRandom`; the selected step is stable for the same seed and
candidate order.

Supported actions are:

```text
THROW_IO
    throw a checked IOException at the selected boundary

HALT
    terminate a child JVM with the selected non-zero status
```

An after-operation point deliberately models an ambiguous outcome: the physical operation completed,
but the caller did not observe successful return. Success metrics are published only after the after
point returns, so an injected after-operation failure remains a failed API operation while recovery
must tolerate the already-completed physical effect.

## Bounded evidence

Each database keeps:

```text
fixed enum occurrence counters
MAX_RECORDED_HITS = 256 recent reached-point records
discarded-hit count
injected checked-I/O count
injected process-halt count
schedule id, seed, and step count
```

The active directory holds weak references only. Shutdown retains at most 64 immutable terminal
snapshots. The fault seam never owns pages, channels, transactions, caches, or database lifetime.

The disabled production path checks its enabled state before constructing a fault context.
It therefore creates no per-operation hit or context objects until a focused test explicitly arms a
schedule. Page I/O remains on the ordinary shared positional-I/O path when injection is disabled.

## Control boundary

Production classes are package-private. Installation and clearing methods are package-private. The
only external bridge lives under `delosdb-tests/src/test/java` in the same package as the production
seam. Normal applications cannot arm a schedule through a JDBC URL, SQL routine, JVM property,
provider configuration, or service lookup.

The package-private proof bridges are excluded from the named `org.apache.derby.tests` source set.
Gradle copies only those sources into a generated test-only source root and compiles them with
`--patch-module org.apache.derby.engine=...`. The ordinary Derby test module is then compiled with
the generated bridge classes patched into `org.apache.derby.engine` and with the existing
test-only implementation package export. At execution time the bridge output is present only on the
focused test classpath and is never added to an engine, storage, or distribution jar. The Gradle
lifecycle uses the neutral `prepareRawStoreInternalTestSupportSources` and
`compileRawStoreInternalTestSupport` task names because the test-support patch serves more than one storage-validation
proof. This avoids an
illegal JPMS split package without widening the production module or using reflection.

This is intentionally stricter than a normal product feature. Destructive failure controls remain
research and verification infrastructure.

## Fallback-write rule

The inherited fallback container may retry a genuine positional growth failure after padding an
alternate random-access implementation. An injected I/O failure is not a sparse-growth failure and
must never enter that retry path. `InjectedIOException` is therefore rethrown directly. This preserves
exact occurrence semantics and prevents an after-write injection from causing a duplicate retry.

## Replay manifest

`DelosRawStoreIoFailureReplayManifest` is immutable test evidence, not a runtime or control API. It
lives under `delosdb-tests` and is absent from `delosdb-derby-store-api`. Schema 1 records:

```text
fault registry version
source revision
environment
seed
database identity
topology
schedule
expected invariant
expected and observed SHA-256 state digests
replay count
reached fault-point count
```

Text fields use URL-safe Base64 in a strict line-oriented format. Unknown, missing, or duplicate keys
are rejected. Expected and observed digests use the same canonical state representation.

## Executable proof

Focused task:

```text
:delosdb-tests:runDelosSharedRawStoreIoFaultInjectionTest
```

The proof covers:

```text
exact point + occurrence matching
stable seeded selection
256-hit bounded history and discarded-hit accounting
disabled-by-default behavior
two active databases with isolated schedules
no effect on an unscheduled database
abrupt child-JVM halt after a completed metadata force
committed heap and MVCC rows surviving reopen
uncommitted DDL remaining absent
identical canonical SHA-256 digest after a second reopen
strict manifest serialization and parse round trip
```

The child process is mandatory for HALT evidence. Cleanup code must not run between the scheduled
point and process termination.

## Non-goals

The fault-injection contract does not add:

```text
arbitrary byte corruption
short-read or short-write simulation
filesystem or device reordering
torn-sector modeling
latency injection
network or multi-process coordination faults
application-visible destructive controls
replacement recovery logic
MemorySegment ownership
mapped files or native/off-heap pages
```

Those require separate design and proof. The heap-backed `MemorySegment` page-buffer experiment and
bounded native physical-I/O mirror retain this fault/replay lane as a standing regression gate. The
mapped-region decision records `NO_GO_FOR_V1_RAWSTORE` for
mapped regions.

## Native-mirror compatibility

The bounded native mirror remains inside the existing before/after page-read, page-write,
force, and channel-reopen boundaries. Fault occurrence and replay semantics do not depend on whether
the physical transfer selected the heap alias or a native mirror.
