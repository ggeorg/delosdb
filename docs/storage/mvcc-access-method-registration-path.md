# MVCC access-method registration path after MODULE18E

This note records the source state after shrinking the remaining MVCC bridge
registration surface. It is intentionally narrow: MODULE18E changes registration
wiring only. It does not delete the bridge, delete `MvccConglomerateFactory`, or
change MVCC storage behavior.

## Decision

`delos_mvcc` no longer uses a direct Derby monitor entry in
`delosdb-engine/src/main/java/org/apache/derby/modules.properties`.

The inherited Derby access-method lookup now reaches MVCC through the neutral
`ExternalAccessMethodProvider` service hook.

## Current registration shape

```text
CREATE TABLE ... USING delos_mvcc
        |
        v
Derby access-method lookup by implementation id
        |
        v
RAMAccessManager.findMethodFactoryByImpl("delos_mvcc")
        |
        +-- first tries inherited Derby monitor lookup
        |     bootServiceModule(... MethodFactory.MODULE, "delos_mvcc", ...)
        |     no direct modules.properties entry exists for delos_mvcc now
        |
        v
fallback service discovery
        |
        v
ServiceLoader<ExternalAccessMethodProvider>
        |
        v
DerbyMvccAccessMethodProvider
        |
        v
MvccConglomerateFactory
```

## Source facts

### 1. `modules.properties` keeps inherited Derby providers only

The engine still directly registers inherited Derby access methods such as heap,
btree, sort, and unique-with-duplicate-nulls sort.

It no longer contains:

```properties
derby.module.access.delos_mvcc=org.apache.derby.impl.store.access.mvcc.MvccConglomerateFactory
cloudscape.config.access.delos_mvcc=all
```

That removes the direct engine-to-bridge registration string.

### 2. `RAMAccessManager` owns the fallback discovery point

`delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/RAMAccessManager.java`
still first uses the inherited Derby monitor path for normal built-in access
methods.

If the monitor path does not return a factory, it calls:

```java
bootExternalAccessMethod(impltype, conglomProperties)
```

That method discovers external providers with:

```java
ServiceLoader.load(ExternalAccessMethodProvider.class)
```

There is also a factory-id fallback through `bootExternalAccessMethodByFactoryId`.

### 3. The neutral hook lives in `delosdb-derby-store-api`

`ExternalAccessMethodProvider` remains in the inherited Derby store API module:

```text
delosdb-derby-store-api/src/main/java/org/apache/derby/iapi/store/access/conglomerate/ExternalAccessMethodProvider.java
```

This keeps the dependency direction clean. Inherited Derby storage knows only a
neutral access-method provider contract, not the concrete MVCC implementation.

### 4. The bridge still provides the Derby access-method adapter

`delosdb-storage-bridge` still owns:

```text
org.apache.derby.impl.store.access.mvcc.DerbyMvccAccessMethodProvider
org.apache.derby.impl.store.access.mvcc.MvccConglomerateFactory
```

`DerbyMvccAccessMethodProvider` supports:

```text
implementation id: delos_mvcc
factory id:        ConglomerateFactory.MVCC_FACTORY_ID
```

and boots `MvccConglomerateFactory`.

### 5. `MvccConglomerateFactory` is still required

Removing the `modules.properties` entry does not remove Derby's need for a
`MethodFactory` / `ConglomerateFactory` adapter.

The service provider now supplies that adapter.

So the current answer is:

```text
remove direct modules.properties registration: yes
remove MvccConglomerateFactory: no
remove delosdb-storage-bridge: no
```

## Why the bridge remains

The bridge is still the inherited Derby access-method compatibility adapter.
It creates and opens MVCC physical conglomerates through Derby's existing store
contracts.

MODULE18E only changes how Derby discovers the adapter.

## Updated MODULE6B meaning

MODULE6B no longer proves direct `modules.properties` registration. It now proves:

- `MvccConglomerateFactory` still owns the `delos_mvcc` implementation id;
- the MVCC factory id is still reserved;
- the bridge publishes `DerbyMvccAccessMethodProvider` through the service descriptor;
- `RAMAccessManager` has the ServiceLoader fallback;
- runtime Derby access-method lookup still discovers `delos_mvcc` by implementation id;
- heap and btree remain unaffected.

## Guardrails

- Do not delete `delosdb-storage-bridge` yet.
- Do not delete `MvccConglomerateFactory` yet.
- Do not move MVCC storage behavior into Derby storage.
- Do not make `delosdb-storage-derby` depend on `delosdb-storage-mvcc`.
- Do not re-add direct `delos_mvcc` engine module registration unless the
  service path fails and the failure is source-proven.
