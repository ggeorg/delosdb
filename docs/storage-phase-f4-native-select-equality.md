# F4 — native MVCC SELECT equality

F4 is the first native Derby execution proof that returns MVCC rows instead of
only reaching a Delos result-set skeleton.

The generated activation still emits the existing Derby table-scan factory call.
There is still no `FromBaseTable.generate()` change, no `AsmJava` change, no
`ResultSetFactory` interface change, and no new bridge SQL route.

The F4 path is property-gated for the proof:

```text
generated activation
  -> GenericResultSetFactory.getTableScanResultSet(...)
  -> DelosTableScanProviderLookup
  -> DelosTableScanResultSet
  -> Qualifier[][] equality translation
  -> EngineMvccTableAccess.scan(...)
  -> Derby ExecRow materialization
```

The bounded F4 translation is:

```text
Qualifier.getColumnId()
  -> TableDescriptor.getColumnDescriptor(int)
  -> ColumnDescriptor.getColumnName()
  -> Qualifier.getOrderable() StoreDataValue
  -> DelosPredicate.equalsTo(...)
```

The smoke proof uses Derby prepared execution for the SELECT. It resets the
transitional bridge route classifier immediately before the prepared SELECT and
asserts that `VersionedStorageSqlBridge.tryExecute(...)` was not called for that
native proof path.

Non-goals:

```text
no native INSERT yet
no native DELETE yet
no native UPDATE yet
no native range predicates yet
no native SELECT * full-scan proof yet
no bridge route expansion
```

F5 moves INSERT onto the native provider-owned execution path.
