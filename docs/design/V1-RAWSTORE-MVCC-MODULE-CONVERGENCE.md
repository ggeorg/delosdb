# V1 RawStore MVCC production module convergence

## Status

```text
VERIFIED
```

## Decision

Stage 7.1 establishes one production owner for the RawStore-backed MVCC access method:
`delosdb-storage-mvcc`.

The former `delosdb-storage-bridge` project is removed only after all of its valid production sources,
service registrations, build wiring, runtime verification, and static contracts move to that owner.
No SQL, transaction, page-format, recovery, locking, vacuum, maintenance, or diagnostics algorithm is
redesigned by this module move.

## Provider implementation ownership

The production `main` source set contains only:

```text
org.apache.derby.impl.store.access.mvcc
META-INF/services/org.apache.derby.iapi.store.access.conglomerate.ExternalAccessMethodProvider
META-INF/services/org.apache.derby.iapi.store.types.DelosStorageDiagnostics
```

It publishes exactly one access-method provider and its own MVCC diagnostics provider. The
Derby-compatible `derby.jar` assembly owns a separate aggregate diagnostics descriptor containing the
inherited heap provider and MVCC provider exactly once. The engine continues to discover the access
method through the neutral `ExternalAccessMethodProvider` contract and does not compile against the
implementation package.

## Retained oracle source deletion

The Phase 8 implementation and its tests were removed after RawStore convergence. Git history and
accepted evidence preserve the experiment; the working tree has no retained source set, no dormant
provider implementation, and no archived page-volume authority.

The production source set therefore contains only the live provider and its two service descriptors.

## derbyRuntimePatchElements

`delosdb-storage-mvcc.jar` is a declared production provider/patch artifact. `delosdb-engine` consumes
it through the non-transitive `derbyRuntimePatchElements` configuration while assembling the current
Derby-compatible `derby.jar`.

This is build wiring, not an engine production-source dependency. Module-boundary analysis continues
to reject `api`, `implementation`, `compileOnly`, or `runtimeOnly` dependencies from the engine to the
MVCC implementation.

## No split-package runtime duplication

The provider artifact is assembled and inspected but is not placed directly on the current runtime
classpath beside `derby.jar`. The provider implementation and service resources are incorporated into
`derby.jar`, preserving the existing JPMS/runtime image while avoiding split-package runtime
duplication.

A later distribution/module-path stage may make the provider jar independently loadable only after
its package and module boundary is proven without patching.

## Removal boundary

The cleanup script removes the obsolete `delosdb-storage-bridge` tree after extraction. Settings,
root assembly, engine patch wiring, module parity, cost-authority paths, documentation, and S0 gates
all point to `delosdb-storage-mvcc`.

## Permanent evidence

```text
delosMvccStorageModuleConvergenceStaticAnalysis
delosV1ModuleArchitectureStaticAnalysis
delosMvccRetainedRuntimeRetirementStaticAnalysis
delosRuntimeArtifactModelStaticAnalysis
verifyDelosRuntimeStorageProviders
```
