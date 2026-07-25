# RawStore storage-I/O implementation retirement

## Final decision

Stage 7.2 removed the standalone `delosdb-storage-io` project. Stage 8.7.3 completes the work by
deleting the archived Delos-native page and volume implementation that had remained inside the old
Phase 8 oracle.

The source-usage audit found **no active production consumer**. RawStore-backed heap and MVCC use the
inherited Derby page, container, logging, recovery, and memory-storage implementation.

## Removed authority

The deleted source archive included:

```text
DelosPage
DelosPageIo
DelosPageVolume
FileChannelPageVolume
MappedPageVolume
OffHeapPageVolume
FaultInjectingPageVolume
```

Moving these classes into another production module would preserve a second page format and storage
authority. Deleting them leaves no second page-volume authority.

## Final result

```text
settings.gradle:                    delosdb-storage-io absent
repository project directory:       absent
archived page-volume source root:   absent
production imports:                 zero
runtime artifact:                   absent
retained source set:                absent
```

Git history preserves the experiment. The working tree contains only the live RawStore I/O path.

## Permanent evidence

```text
delosStorageIoModuleRetirementStaticAnalysis
delosMvccRetainedRuntimeRetirementStaticAnalysis
delosV1ModuleArchitectureStaticAnalysis
delosModuleDependencyBoundaryStaticAnalysis
```
