# Storage Phase N1.2 — Direct RowChanger-backed heap INSERT proof

N1.2 is a direct proof only.

It does not activate heap mutation routing and it does not add a heap mutation
provider. The purpose is narrower: prove that the minimum context identified in
N1.1 can be used to perform a heap INSERT through Derby's existing RowChanger
path outside the normal SQL INSERT result-set route.

## Decision

The safe result after N1.2 is:

```text
Direct RowChanger-backed heap INSERT is feasible as a proof.
Heap SQL INSERT must remain Derby-owned.
Do not start N2 yet.
```

## Proof shape

The N1.2 smoke creates an ordinary Derby heap table and then performs one direct
insert using:

```text
EmbedConnection.getLanguageConnection()
LanguageConnectionContext.getTransactionExecute()
DataDictionary / TableDescriptor metadata lookup
TransactionController.getStaticCompiledConglomInfo(...)
TransactionController.getDynamicCompiledConglomInfo(...)
ExecutionFactory.getRowChanger(...) directly
RowChanger.open(...)
RowChanger.insertRow(...) directly
RowLocation returned by RowChanger
```

The row is then read back through ordinary SQL to prove the heap mutation reached
Derby's store.

## Non-goals

N1.2 deliberately does not add:

```text
No SQL routing change.
No DelosHeapInsertResultSet.
No EngineHeapMutableTableAccess.
No heap mutation provider registration.
No generic Delos mutation API.
No heap row reservation API.
No heap locking abstraction.
No DELETE / UPDATE heap mutation proof.
No N2 heap INSERT live path.
```

## Why this remains honest

Derby heap mutation is still centered on `RowChanger`, `ConglomerateController`,
and index-maintenance machinery. N1.2 does not hide that behind a premature Delos
contract. It proves the first direct heap mutation step while keeping production
SQL INSERT behavior unchanged.

## Next safe step

The next safe step is still not N2. The safer sequence is:

```text
N1.3 — direct RowChanger-backed heap DELETE / UPDATE proof, not SQL-routed
N1.4 — indexed heap INSERT proof if needed
N2   — heap INSERT live path only if the direct proofs stay honest
```
