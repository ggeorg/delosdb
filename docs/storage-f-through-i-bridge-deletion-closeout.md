# DelosDB storage F-I bridge deletion closeout

Status: complete after the bridge-deletion closeout smoke is green.

This closeout removes the retired pre-parse SQL bridge from the active source
surface. Supported `delos_mvcc` SQL now uses Derby's normal parser, binder,
optimizer, generated activation, ResultSetFactory, and native Delos result-set
paths.

## Closed lane

```text
Phase F — native Derby execution path: complete
Phase G — native predicate/index coverage and bridge fallback retirement: complete
Post-G — native table registry extraction and restart/reopen proof: complete
Phase H — cost observation/cost constant proofs: complete
Phase I Option A — optimistic mutation preparation and conflict mapping: complete
```

## Bridge deletion boundary

The closeout requires all of the following to be true:

```text
- no VersionedStorageSqlBridge.java source file
- no VersionedStorageSqlResult.java bridge result wrapper
- no EmbedStatement pre-parse interception hook
- no EmbedConnection bridge commit/rollback callbacks
- no soft-G6 compatibility property wiring
- no active F/G/H/I smoke assertion that depends on bridge route counters
- native CREATE / INSERT / SELECT still works after bridge deletion
- native registry still reconstructs table access after Derby shutdown/reopen
```

## Remaining native boundary

`DelosNativeTableRegistry` is the native catalog/provider boundary. It is not a
SQL router. It is reached after Derby metadata has identified a `delos_mvcc`
table.

The bridge-era C-phase and `versioned-storage-sql-metadata-smoke` sources are
retired archaeology. They are removed by the closeout cleanup script instead of
being kept as active tests.
