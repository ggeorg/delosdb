# MVCC access-method registration path after MODULE18C

This note records the source state before shrinking the remaining MVCC bridge
registration surface. It is intentionally source-only. It does not add tests,
guards, runtime code, or registration changes.

## Question

Can `delos_mvcc` registration move fully to service/provider discovery, or does
inherited Derby still require `modules.properties` / `MvccConglomerateFactory`?

## Current source facts

### 1. The engine still declares `delos_mvcc` in `modules.properties`

`delosdb-engine/src/main/java/org/apache/derby/modules.properties` still contains
Derby's monitor-style access-method registration:

```properties

derby.module.access.delos_mvcc=org.apache.derby.impl.store.access.mvcc.MvccConglomerateFactory
cloudscape.config.access.delos_mvcc=all
```

This means the inherited Derby module monitor has a direct name-to-class route
for the MVCC access method.

### 2. `RAMAccessManager` also has service-provider fallback

`delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/RAMAccessManager.java`
first tries the inherited Derby monitor path:

```java
factory = (MethodFactory) bootServiceModule(
        false, this, MethodFactory.MODULE,
        impltype, conglomProperties);
```

If the monitor path returns no factory because the implementation is missing,
`RAMAccessManager` falls back to the neutral service hook:

```java
factory = bootExternalAccessMethod(impltype, conglomProperties);
```

That fallback iterates:

```java
ServiceLoader.load(ExternalAccessMethodProvider.class)
```

and asks each provider whether it supports the requested implementation id.
There is also a factory-id path through `bootExternalAccessMethodByFactoryId(int
factoryId)`, which asks providers for `ConglomerateFactory.MVCC_FACTORY_ID`.

### 3. The neutral service hook lives in the Derby store API module

`delosdb-derby-store-api/src/main/java/org/apache/derby/iapi/store/access/conglomerate/ExternalAccessMethodProvider.java`
contains the neutral contract:

```java
boolean supportsImplementation(String implementationId);
boolean supportsFactoryId(int factoryId);
MethodFactory bootForImplementation(...);
ConglomerateFactory bootForFactoryId(...);
```

This is the correct direction: inherited Derby storage can discover an external
access method without depending on `delosdb-storage-mvcc` or on a concrete
MVCC provider implementation.

### 4. The bridge currently provides the external Derby access-method provider

`delosdb-storage-bridge/src/main/java/org/apache/derby/impl/store/access/mvcc/DerbyMvccAccessMethodProvider.java`
implements `ExternalAccessMethodProvider`.

It supports:

```java
MvccConglomerateFactory.IMPLEMENTATION_ID.equals(implementationId)
factoryId == ConglomerateFactory.MVCC_FACTORY_ID
```

and boots a `MvccConglomerateFactory` when Derby asks for either the
implementation id or the reserved MVCC factory id.

### 5. The service descriptor is owned by the bridge and packaged into `derby.jar`

`delosdb-storage-bridge/src/main/resources/META-INF/services/org.apache.derby.iapi.store.access.conglomerate.ExternalAccessMethodProvider`
contains:

```text
org.apache.derby.impl.store.access.mvcc.DerbyMvccAccessMethodProvider
```

`delosdb-engine/build.gradle` packages that descriptor into the compatibility
runtime `derby.jar` from `storageImplementationPatch`:

```groovy
include 'META-INF/services/org.apache.derby.iapi.store.access.conglomerate.ExternalAccessMethodProvider'
```

So the service-provider path is not just theoretical. It is wired into the
runtime artifact.

### 6. `MvccConglomerateFactory` is still required

Even if the `modules.properties` entry is removed later, Derby still needs a
`MethodFactory` / `ConglomerateFactory` object for `delos_mvcc`.

The service provider currently supplies exactly that by creating
`MvccConglomerateFactory`.

So the answer is not:

```text
remove MvccConglomerateFactory
```

The possible next shrink is only:

```text
remove direct modules.properties registration and rely on ExternalAccessMethodProvider discovery
```

while keeping `MvccConglomerateFactory` as the Derby access-method adapter.

## Current registration shape

```text
CREATE TABLE ... USING delos_mvcc
        |
        v
Derby access-method lookup by implementation id
        |
        +-- primary path:
        |     modules.properties
        |       derby.module.access.delos_mvcc
        |       -> MvccConglomerateFactory
        |
        +-- fallback path:
              ServiceLoader<ExternalAccessMethodProvider>
                -> DerbyMvccAccessMethodProvider
                -> MvccConglomerateFactory
```

## Decision

`delos_mvcc` appears capable of moving off the direct `modules.properties`
registration because `RAMAccessManager` already has a service-provider fallback
and the bridge already publishes the provider descriptor.

But `MvccConglomerateFactory` cannot be removed yet. It remains the concrete
Derby `ConglomerateFactory` adapter that inherited Derby expects once the MVCC
access method is discovered.

## Risk before changing it

Some historical smoke/source checks may still assert the old direct
`modules.properties` route. In particular, the older MODULE6B smoke still reads
source text and expects the `modules.properties` entry. That assertion is stale
relative to the current architecture direction if MODULE18E moves registration
to service discovery.

Therefore MODULE18E must not silently delete the entry alone. It must either:

1. update the relevant historical smoke assertion to the new service-discovery
   registration fact, or
2. leave the `modules.properties` entry in place and only document that service
   discovery exists.

## Recommended MODULE18E

Safe next overlay:

- remove the two `delos_mvcc` lines from `delosdb-engine/src/main/java/org/apache/derby/modules.properties`, or keep them only if a proof shows the monitor path is still required;
- update MODULE6B's stale source assertion, if it is still compiled/run by the
  build, to check the service descriptor and `ExternalAccessMethodProvider`
  fallback instead of the old direct `modules.properties` line;
- run the clean proof plus the original access-method registration smoke.

Suggested proof block:

```bash
./gradlew clean
./gradlew :module6b-mvcc-access-method-registration-smoke:run
./gradlew :module13-derby-lifecycle-mvcc-wal-pagelsn-smoke:run
./gradlew :module14-derby-visible-mvcc-checkpoint-smoke:run
./gradlew :module15-derby-safe-mvcc-vacuum-horizon-smoke:run
./gradlew :module16-derby-access-compatible-candidate-index-smoke:run
./gradlew build
./scripts/module-dependency-tree.py
```

## Guardrails

- Do not delete `delosdb-storage-bridge` in MODULE18E.
- Do not delete `MvccConglomerateFactory` in MODULE18E.
- Do not move MVCC storage behavior into Derby storage.
- Do not make `delosdb-storage-derby` depend on `delosdb-storage-mvcc`.
- Do not add a guard or audit smoke for this source-study pass.
