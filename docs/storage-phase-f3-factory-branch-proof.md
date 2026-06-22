# F3.1 — provider-aware ResultSetFactory branch proof

F3.1 proves the first native Derby execution seam for Delos-backed table scans.
It does not execute MVCC rows yet. It proves that the existing generated Derby
bytecode shape is enough to reach a provider-aware branch point.

## Verified shape

```text
generated activation
  -> ResultSetFactory.getTableScanResultSet(..., tableName, ...)
  -> GenericResultSetFactory.getTableScanResultSet(...)
  -> DelosTableScanProviderLookup.observeFactoryLookupIfEnabled(...)
  -> DataDictionary / TableDescriptor.storageProviderName
  -> normal Derby TableScanResultSet for F3.1
```

The proof keeps the hard inherited compiler surfaces untouched:

```text
no FromBaseTable.generate() change
no AsmJava change
no ResultSetFactory method-shape change
no getDelosTableScanResultSet method
F3.2 may add a DelosTableScanResultSet skeleton without changing this F3.1 bytecode-shape proof
```

## Why this is intentionally narrow

F2.2 proved the lookup helper directly. F3.1 proves the same lookup is reachable
from the real table-scan factory branch that generated activations already call.
The observation is gated by `DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY`
so ordinary Derby scans do not pay this proof lookup cost.

## Smoke proof

The smoke creates a table through Derby prepared execution:

```sql
CREATE TABLE F3_FACTORY_BRANCH_MVCC (id INT, value VARCHAR(32)) USING delos_mvcc
INSERT INTO F3_FACTORY_BRANCH_MVCC VALUES (1, 'alpha')
```

After restart it enables the F3.1 probe, runs a prepared SELECT, and asserts:

```text
GenericResultSetFactory observed a non-default provider.
The observed provider is delos_mvcc.
The observed table is F3_FACTORY_BRANCH_MVCC.
VersionedStorageSqlBridge was not invoked.
```

## Follow-up

F3.2 adds a property-gated `DelosTableScanResultSet` skeleton branch for
`delos_mvcc` tables while default heap tables continue returning Derby scan
result sets.  F4 replaces that skeleton sentinel with real MVCC scan
materialization.
