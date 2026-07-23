# V1 RawStore MVCC retained production runtime retirement

## Status

```text
VERIFIED
```

## Decision

The independent Phase 8 MVCC persistence system is no longer a production runtime option.
`delos_mvcc` always uses inherited Derby RawStore.

This is a production cut, not another proof retargeting:

```text
factory branch removed
retained Phase 8 classes excluded from production compilation
external provider service removed
retained MVCC and page-volume jars removed from runtime classpaths
normal retained test graph removed from check and S0
RawStore SQL integration becomes the normal lane
```

## Factory and access-method boundary

`MvccConglomerateFactory.boot()` always constructs one `MvccRawStoreRuntime`. The historical opt-in
property is ignored. A missing RawStore descriptor fails closed; no retained controller is created.

`MvccConglomerate` contains only RawStore controller/scan/drop/vacuum paths. The production
`delosdb-storage-mvcc` main source set contains only the RawStore provider implementation. The retained
Phase 8 runtime, controllers, formats, and page-volume code compile only in `legacyRetained`.

## Artifact boundary

The normal runtime artifact list contains `derby.jar`, where the neutral
`DerbyMvccAccessMethodProvider` service is packaged. `delosdb-storage-mvcc.jar` is assembled and
verified as the production provider/patch artifact but is not placed beside `derby.jar` on the current
runtime classpath. `delosdb-storage-io.jar` remains absent.

The retained source remains temporarily as an explicit historical oracle, but:

```text
root jars assembles the production delosdb-storage-mvcc.jar patch artifact but not delosdb-storage-io.jar
normal module check does not compile the retained implementation
normal SQL test compilation excludes the page-volume recovery differential
its DelosStorageProviderFactory service resource is excluded
its provider factory class is excluded
module-info no longer declares the retired service use
runtime verification rejects both retained jars from the classpath
standalone JMH verifies DerbyMvccAccessMethodProvider from derby.jar
optional tools no longer require the retired page-volume module
legacyRetainedCheck is the only aggregate which builds and runs the archive
```

## Verification boundary

Normal verification now means:

```text
:delosdb-storage-mvcc:check
    -> quarantine verification only
    -> no retained source compilation

:delosdb-tests:check
    -> RawStore SQL/DRDA integration and security

s0CloseoutVerification
    -> active architecture and RawStore gates
    -> one consolidated retained-runtime retirement gate
```

The old Phase 8 durability suite is quarantined behind:

```text
:delosdb-storage-mvcc:legacyRetainedCheck
```

It is not a production gate and cannot pull the retained provider into a normal runtime.

## Diagnostics boundary

`MvccStorageDiagnostics` observes only `MvccRawStoreRuntime`. Retained database-storage snapshot APIs
fail explicitly because their authority was retired. The weak diagnostics directory never owns or
closes a runtime.

## Fail-closed compatibility

Existing retained-format files are neither read nor modified. Their presence rejects RawStore boot.
The old property cannot bypass this guard. This intentionally requires an external migration/export
step before such a database can run on the final format.

## Permanent gate

`delosMvccRetainedRuntimeRetirementStaticAnalysis` checks the factory, active MVCC source sets,
module-info, runtime artifact model, class-directory runtime, standalone JMH runtime, optional-tools
dependencies, ServiceLoader boundary, quarantined provider resource, normal and legacy verification
graphs, RawStore SQL integration lane, and this design/roadmap record.

## Not included

This slice does not yet:

```text
delete every archived Phase 8 source and test file
perform retained-format migration
retire final transitional modules
start JDK 25 I/O modernization
begin Lucene work
```
