# Storage Phase S4 — File-Backed Page Volume Boundary

S4 introduces the first concrete implementation of the `DelosPageVolume`
contract: `FileChannelPageVolume`.

This is still part of the DelosDB Unified Storage I/O Abstraction phase. It is
not an MVCC redesign and it is not heap provider work.

## Scope

S4 adds a file-backed page-volume implementation inside `delosdb-storage-io`.
The implementation owns only raw page I/O:

- page count
- page allocation
- complete page reads
- complete page writes
- force / fsync boundary
- sync policy
- close

## Boundary rule

`FileChannelPageVolume` does not wrap `MvccPageFile` and does not import from
`delosdb-storage-mvcc`. This preserves the required dependency direction:

```text

delosdb-storage-io
    ↑ consumed by
 delosdb-storage-mvcc
```

The storage I/O module remains neutral. It must not know MVCC visibility,
transaction state, recovery policy, SQL execution, Derby heap internals, or
provider dispatch.

## Non-goals

S4 does not:

- migrate page-backed MVCC callers
- alter `MvccPageFile`
- alter page format
- add `path()` to `DelosPageVolume`
- change rewrite / compact lifecycle
- add OffHeap or FaultInjecting volumes
- introduce MemorySegment

## Proof

The S4 smoke proves:

- a new file-backed volume starts empty
- allocated pages get stable sequential page ids
- page data survives close and reopen
- `SyncPolicy.FULL`, `METADATA_ONLY`, and `NONE` are accepted
- out-of-range page reads fail explicitly

The O5 and C7 smokes remain mandatory regression protection so heap/provider
behavior is protected without being migrated into the new page-volume layer.
