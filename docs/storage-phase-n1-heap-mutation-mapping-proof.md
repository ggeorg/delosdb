# Storage Phase N1 — Heap mutation mapping proof

## Purpose

N1 answers whether the M3 heap read route should immediately continue into heap
INSERT / DELETE / UPDATE live-provider routing.

The answer is **not yet**.

Heap SELECT has a narrow, supported-shape live route after M3, but heap mutation
is still owned by Derby's existing mutation executor stack:

```text
INSERT:
  InsertResultSet
    -> ExecutionFactory.getRowChanger(...)
    -> RowChangerImpl.open(...)
    -> ConglomerateController.insert(...) / insertAndFetchLocation(...)
    -> IndexSetChanger index maintenance when indexes exist

DELETE:
  DeleteResultSet
    -> source row with RowLocation column
    -> RowChangerImpl.deleteRow(...)
    -> ConglomerateController.delete(...)
    -> IndexSetChanger delete maintenance when indexes exist

UPDATE:
  UpdateResultSet
    -> source row with RowLocation column
    -> sourceResultSet.updateRow(...)
    -> RowChangerImpl.updateRow(...)
    -> ConglomerateController.replace(...)
    -> IndexSetChanger update maintenance when indexes exist
```

## Source-backed decision

N1 decision: **defer heap mutation parity**.

RowChangerImpl is not just a low-level heap write primitive. It owns or
coordinates important Derby mutation behavior:

```text
- update-open mode and lock mode selection
- compiled/uncompiled ConglomerateController opening
- Activation heap controller sharing
- RowLocation-dependent DELETE / UPDATE
- partial-row update bitsets
- index insert / delete / update maintenance
- deferred-row handling through existing callers
- finish/close lifecycle tied to the Derby execution stack
```

That means a generic Delos mutation contract would be dishonest if it looked like
only this:

```text
insert(row)
delete(rowIdentity)
update(rowIdentity, row)
```

The heap implementation would need far more Derby execution context than that
contract admits. Hiding RowChangerImpl behind a generic DelosMutableTableAccess
would make the contract look provider-neutral while secretly depending on
Activation, compiled conglomerate metadata, index metadata, RowLocation flow,
source result-set behavior, and Derby's RowChanger lifecycle.

## What N1 proves

N1 proves the current mutation truth only:

```text
- heap INSERT still routes through InsertResultSet and RowChangerImpl
- heap DELETE still routes through DeleteResultSet, RowLocation, and RowChangerImpl
- heap UPDATE still routes through UpdateResultSet, RowLocation, and RowChangerImpl
- RowChangerImpl is coupled to ConglomerateController and IndexSetChanger
- M3 heap SELECT live route remains read-only
- EngineHeapTableAccessLiveCandidate remains scan-only
- no generic heap mutation provider API is introduced
- no heap Delos mutation result sets appear
```

## Comparison with uploaded reference source

The uploaded PostgreSQL source has a table access method boundary for mutations,
with table tuple insert/delete/update calls under `TableAmRoutine` in
`src/include/access/tableam.h`.

The uploaded MariaDB source has handler-level write/update/delete methods, but
its contract is an engine boundary with the server explicitly routing mutations
through handler methods.

The uploaded Apache Calcite source separates table modification planning through
`TableModify` / `ModifiableTable` style interfaces rather than pretending that a
read-only scan interface is also a mutation contract.

Those comparisons support the same local rule used here: do not add a generic
mutation-provider method until both providers can implement the whole contract
honestly.

## Consequence

Do **not** start N2 yet.

N2 heap INSERT live path is not accepted by N1. N3 heap DELETE / UPDATE live path
is also not accepted by N1.

The safe next work is one of these, in this order:

```text
N1.1 — define the minimum honest heap mutation context shape, still no routing
N1.2 — direct RowChanger-backed heap INSERT proof, not SQL-routed
N1.3 — direct RowChanger-backed heap DELETE / UPDATE proof, not SQL-routed
```

Only after those proofs should DelosDB decide whether a real heap mutation
provider contract exists.
