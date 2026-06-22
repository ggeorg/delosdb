# Storage Phase F0/F1a Start

F0/F1a begins the native Derby execution plan without implementing native
execution yet.

## F0 boundary

`VersionedStorageSqlBridge` is now treated as transitional scaffolding.  It may
remain for compatibility smokes while native execution is built, but it must not
keep growing into a second SQL executor.

Rules:

```text
- no new bridge-only SQL routes
- no new regex route declarations
- no C38 route-polishing lane
- no new manual SQL execution path in VersionedStorageSqlBridge
```

## F1a confirmation

The parser grammar is already provider-aware:

```text
sqlgrammar.jj has storageProviderClause()
CreateTableNode stores storageProviderName
TableDescriptor stores storageProviderName in memory
```

F1a only confirms the parser/prepare path accepts `CREATE TABLE ... USING
delos_mvcc`.  It does not change grammar, metadata persistence, or execution.

## Tracked smoke database cleanup

The ignore rules prevent new `storage-phase-*-db` directories from being added.
If any generated Derby database files were already tracked before the ignore rule
landed, remove them from version control once using the IDE/Git index workflow.
Do not commit generated database binaries.
