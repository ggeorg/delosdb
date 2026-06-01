# DelosDB Modernization Status

Last updated: 2026-05-31

## Current status

DelosDB is now a Gradle-only Java 21 modernization fork of Apache Derby. Ant is no longer the supported workflow.

The current modernization baseline is green across the standard gate:

```bash
./gradlew clean build --warning-mode all
./gradlew fullVerification --warning-mode all
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
grep -n "Vector usage\|Hashtable usage\|Remaining candidate\|Deferred" build/reports/modernization/modernization-audit.md
```

## Build and verification status

Confirmed green:

- `clean build`
- `clean build --warning-mode all`
- `fullVerification`
- `fullVerification --warning-mode all`
- embedded smoke test
- smoke test from assembled jars
- Java 21 modernization smoke test
- Network Server smoke test
- `sysinfoFromJars`
- modernization audit verification
- embedded benchmark baseline

The Gradle 9 deprecation warnings for deprecated `exec {}` / `javaexec {}` usage have been removed by switching build execution helpers to `ExecOperations`.

## Java 21 modernization status

Completed:

- Removed production `Object.finalize()` usage.
- Added Cleaner fallback only where needed.
- Removed production `System.getSecurityManager` usage.
- Removed production `AccessController` usage.
- Removed production `doPrivileged` usage.
- Added modernization audit verification.
- Added Java 21 modernization smoke coverage.
- Added Network Server smoke coverage.
- Added embedded benchmark baseline.
- Reduced low-risk `Vector` / `Hashtable` usage.
- Classified the remaining legacy collection tail into deferred/candidate audit sections.
- Reduced Java 21 warning noise around reflection varargs, deprecated annotations, staged module warnings, localization package markers, and generated ij char streams.

## Current modernization audit counts

Current audit status:

```text
Vector usage: 42
Hashtable usage: 33
```

The remaining audit report includes:

- production `Vector` usage
- production `Hashtable` usage
- deferred/concurrency-sensitive `Vector` usage
- deferred/contract-sensitive `Hashtable` usage
- remaining candidate `Vector` usage
- remaining candidate `Hashtable` usage

At this checkpoint, remaining candidate sections are expected to be empty or near-empty. Remaining legacy collection usage should be treated as deferred unless reviewed explicitly.

## Deferred collection areas

Do not casually modernize these areas without a focused design review:

- monitor / daemon lifecycle structures
- store / recovery / sorting structures
- lock / deadlock / timeout structures
- DRDA runtime queues and global session tables
- data dictionary public or serialization-sensitive APIs
- compiler query-tree structures
- runtime-statistics public/internal compatibility surfaces
- activation parent result-set contracts
- `FormatableHashtable`
- `ProviderList`
- `ClassSizeCatalog`
- `DiskHashtable`
- old `Dictionary` / JNDI / property APIs that intentionally require `Hashtable`

## Current release and artifact foundation

Confirmed in place:

- Gradle wrapper
- extracted subprojects
- jar verification
- release metadata verification
- binary ZIP/tar.gz distribution foundation
- Maven Local publication baseline
- `verifyReleaseArtifacts`
- `verifyReleaseDistribution`
- `sysinfoFromJars`
- `smokeFromJars`

## Standard validation gate

Use this gate after most overlays:

```bash
./gradlew clean build
./gradlew fullVerification
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
grep -n "Vector usage\|Hashtable usage\|Remaining candidate\|Deferred" build/reports/modernization/modernization-audit.md
```

For Gradle compatibility work, use:

```bash
./gradlew clean build --warning-mode all
./gradlew fullVerification --warning-mode all
```

## Recommended next work

Recommended next lane:

1. Stop broad `Vector` / `Hashtable` replacement for now.
2. Review Gradle build structure for remaining Gradle 9 compatibility risks beyond `exec` / `javaexec`.
3. Add a release-readiness checklist for DelosDB distributions.
4. Consider making modernization audit output part of a generated CI artifact.
5. Only revisit deferred collection areas one subsystem at a time with explicit design notes.

## Commit comment for this checkpoint

```text
Document current DelosDB modernization checkpoint
```
