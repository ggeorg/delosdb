# MVCC ordered-index NULL-key proof

The current proof is a **RawStore-backed SQL proof**. The retired Phase 8 ordered-index page-store
oracle and its unit test are no longer part of the working tree.

## Required behavior

`DelosStorageOrderedIndexKey.encode(value)` remains the typed Derby-value boundary. SQL `NULL` uses
the stable typed NULL envelope, rows containing NULL values survive shutdown and reopen, ordered-index
summaries retain those entries, supported non-NULL ranges continue to use ordered pages, and the
provider remains internally consistent.

The executable authority is:

```text
MvccSqlTypedOrderedIndexKeyTest#testNullValuesKeepTypedOrderedIndexKeySemanticsThroughReopen
```

The proof does not introduce generic Java object serialization, a second index format, candidate-index
authority, or a new optimizer rule for `IS NULL`.

## Verification

```bash
./gradlew \
  delosMvccOrderedIndexNullKeyProofStaticAnalysis \
  :delosdb-tests:runDelosMvccSqlIntegrationTest \
  --console=plain
```
