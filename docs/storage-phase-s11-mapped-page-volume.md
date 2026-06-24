# Storage Phase S11 — MappedPageVolume Candidate

S11 adds `MappedPageVolume` as an optional, benchmark-gated implementation of
`DelosPageVolume`.

This is not the default storage backend. It is not a Java baseline switch. It
uses the existing Java 21-compatible memory-mapped file API and keeps the S10
foreign-memory migration gate intact.

## Scope

In scope:

- `MappedPageVolume`
- same `DelosPageVolume` contract
- same `DelosPage` / `DelosPageId` primitives
- bounded mapped-volume candidate
- smoke coverage for reopen, page bounds, sync policy, and range failures

Out of scope:

- caller migration
- recovery rewiring
- heap/provider work
- provider dispatch
- default-backend change
- Java 25 migration
- foreign-memory migration

## Boundary

`MappedPageVolume` must not import MVCC, Derby, heap, SQL, or provider-dispatch
classes. It is a storage I/O implementation only.
