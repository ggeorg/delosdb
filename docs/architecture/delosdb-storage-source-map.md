# DelosDB storage source map

The storage split is a justified project boundary because DelosDB has more than one real storage
implementation path: inherited Derby heap/raw/btree storage and native DelosDB MVCC storage.  This
is a real education and research axis, not an aesthetic package split.

## Current storage layout

```text
delosdb-storage-api
  provider-neutral DelosDB storage contracts

delosdb-derby-store-api
  inherited Derby store contracts

delosdb-storage-derby
  inherited Derby heap/raw/btree implementation

delosdb-storage-mvcc
  native DelosDB MVCC implementation

delosdb-storage-bridge
  temporary Derby access-method compatibility adapter

delosdb-storage-io
  low-level storage I/O and page/WAL-oriented support where applicable
```

## Intended architecture

```text
                         delosdb-storage-api
                           ↑              ↑
                           |              |
              delosdb-storage-derby   delosdb-storage-mvcc
                           |              |
                    inherited Derby      native MVCC
                    heap/raw/btree       page/WAL/checkpoint
```

The bridge should not be the abstraction center. It exists to connect inherited Derby access-method
paths to DelosDB storage providers while the source is still being disentangled.

The bridge must not become the shared architecture layer between `delosdb-storage-derby` and
`delosdb-storage-mvcc`. It must not own provider contracts, common btree/sort code, or the final
storage-provider shape. The target relationship is the two-provider shape above, where both storage
providers answer to `delosdb-storage-api` rather than one provider reaching another through the
bridge.

## Educational meaning

The storage layout should let a student distinguish:

```text
storage contract
storage provider
heap access
btree/index access
MVCC visibility
WAL/durable recovery
checkpointing
vacuum / garbage collection
bridge compatibility code
```

## Research meaning

The storage layout should let a researcher compare and instrument:

```text
Derby heap/raw/btree access
DelosDB MVCC page/version access
predicate pushdown and leftover predicates
visibility and snapshot behavior
WAL/checkpoint/vacuum behavior
storage-provider selection from the engine
```

## Current decision rules

```text
Do not merge storage back into delosdb-engine.
Do not make the bridge the shared implementation layer.
Do not put shared btree, sort, or storage-provider contracts in the bridge.
Do not create a fake common storage implementation between Derby storage and MVCC.
Do keep the provider-neutral storage API small and honest.
Do expose storage behavior through diagnostics and the modern RDBMS model.
```

## Relationship to the modern RDBMS model

The modern RDBMS model should name storage behavior through model concepts such as:

```text
RdbmsStorageProviderKind
RdbmsStorageAccessKind
RdbmsTransactionConcept
```

Trace events may record those model facts, and diagnostics may summarize them, but trace and
diagnostics are not the storage model themselves.

The first useful storage observations are:

```text
which provider handled the access
which access method was used
whether predicates were pushed down
whether leftover predicates remained
how many rows flowed back to the executor
which snapshot or visibility boundary applied for MVCC
```
