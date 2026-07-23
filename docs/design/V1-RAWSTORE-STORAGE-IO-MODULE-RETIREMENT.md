# RawStore storage-I/O module retirement

## Decision

Stage 7.2 removes the standalone `delosdb-storage-io` Gradle project.

The source-usage audit found **no active production consumer** of its Delos-native page and volume
model. All 13 Java sources are referenced only by the quarantined Phase 8 MVCC differential oracle
and its archived tests. The current RawStore-backed heap and MVCC paths use inherited Derby
containers, pages, logging, recovery, and memory storage instead.

## Why the classes do not move into production RawStore modules

The retired module defines a separate authority:

```text
DelosPage
DelosPageIo
DelosPageVolume
FileChannelPageVolume
MappedPageVolume
OffHeapPageVolume
FaultInjectingPageVolume
```

Moving those types into active `delosdb-storage-derby` or `delosdb-runtime-api` production source
sets would preserve a second page format and page-volume authority beside RawStore. That would
contradict the convergence invariant rather than complete it.

The useful production behavior already exists in inherited RawStore. Stage 8 may modernize that
shared implementation directly; it must not revive `DelosPageVolume` as a provider-selectable backend.
This leaves no second page-volume authority in production.

## Retained-oracle placement

The 13 source files move unchanged into:

```text
delosdb-storage-mvcc/src/main/java/io/github/ggeorg/delosdb/storage/io/**
```

The legacyRetained source set compiles them together with the archived Phase 8
MVCC implementation. They are excluded from:

```text
main production classes
delosdb-storage-mvcc.jar
derby.jar
normal runtime classpaths
normal check
S0 runtime tests
```

`legacyRetainedCheck` remains the explicit opt-in entry point for the archived differential oracle.

## Module and artifact result

```text
settings.gradle:                 delosdb-storage-io absent
repository module directory:    removed
delosdb-storage-io.jar:         not assembled or distributed
active production imports:      zero
legacyRetained source count:    13 page-volume sources plus the archived MVCC oracle
```

At the Stage 7.2 checkpoint, the repository had 21 Gradle subprojects:

```text
21 Stage 7.2 modules
+ 1 delosdb-search-lucene
- 1 then-remaining transitional module (delosdb-storage-api)
= 21 final modules
```

Stage 7.3 subsequently retires storage-api and leaves 20 current modules before the Lucene addition.

## Permanent evidence

```text
delosStorageIoModuleRetirementStaticAnalysis
delosV1ModuleArchitectureStaticAnalysis
delosModuleDependencyBoundaryStaticAnalysis
delosRuntimeArtifactModelStaticAnalysis
:delosdb-storage-mvcc:legacyRetainedCheck
```
