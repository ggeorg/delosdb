# Storage Phase S14 — JDK 25 Baseline Closeout

S14 switches the DelosDB build baseline from Java 21 to Java 25 after the Gradle wrapper update.

This is platform work, not storage behavior work.

## Changes

- `javaRelease` is now 25.
- The storage I/O guard-only MemorySegment gate task is retired.
- The storage I/O boundary source-shape guard task is retired.
- The storage I/O module keeps its behavior smokes.

## Not changed

- No `MemorySegment` implementation yet.
- No page format change.
- No `DelosPageVolume` contract change.
- No heap/provider migration.
- No recovery policy change.
- No default backend change.

## Expected proof

Run the storage I/O behavior smokes, MVCC compile/smokes, O5 provider parity, and C7 stabilization on JDK 25.
