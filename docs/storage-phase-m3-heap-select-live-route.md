# Storage Phase M3 — Heap SELECT live route for supported shapes

M3 is the first deliberately narrow heap SQL read route that crosses a Delos table-access object.

The branch is enabled only with:

```text
delosdb.storage.phaseM3.heapSelectLiveRoute=true
```

## Decision

M3 keeps the K1/M1/M2 safety boundary intact while moving one capability forward:

```text
heap SELECT, supported read-only base-table shapes only:
  GenericResultSetFactory
    -> DelosHeapLiveTableScanResultSet
    -> EngineHeapTableAccessLiveCandidate.scan(...)
    -> TransactionController.openCompiledScan(...)
    -> ScanController.fetchNext(...)
    -> Derby ExecRow materialization
```

Unsupported heap shapes still fall back to Derby's normal route:

```text
TableScanResultSet / BulkTableScanResultSet
```

## Supported in M3

```text
- default-provider heap table only
- read-only SELECT only
- base heap table scan only
- Derby-owned qualifiers passed through to ScanController
- Derby-owned projection/restriction layers above the scan
- no native table-registry registration for heap
```

## Explicitly not supported in M3

```text
- heap INSERT / DELETE / UPDATE live route
- heap row reservation
- heap lock abstraction
- generic DelosMutableTableAccess.tryLock(...)
- index-name scans
- keyed start/stop scan routing
- for-update scans
- heap provider registration in DelosNativeTableRegistry
- bridge resurrection
```

## Why this is still A-lite, not full A

A remains the destination: heap and delos_mvcc eventually become full live providers under one Delos storage contract.

M3 does not claim full provider parity. It activates only the read scan capability for narrowly supported heap SELECT shapes. Mutation and locking stay owned by Derby's existing RowChangerImpl / ConglomerateController paths until N-phase mapping proves they can be represented honestly.

## Acceptance

`verifyStoragePhaseM3HeapSelectLiveRoute` proves:

```text
- M3 flag disabled: heap SELECT remains Derby-native
- M3 flag enabled: supported heap SELECT reaches DelosHeapLiveTableScanResultSet
- supported heap SELECT reads through EngineHeapTableAccessLiveCandidate.scan(...)
- WHERE qualifiers still return correct heap rows
- projection still returns correct heap rows
- delos_mvcc SELECT still uses the native MVCC route before heap live route
- heap is still absent from DelosNativeTableRegistry
- heap mutations remain RowChanger / ConglomerateController-owned
- no heap lock/reservation API appears
```
