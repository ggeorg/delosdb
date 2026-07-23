# MVCC ordered-index NULL key proof

This proof closes the first semantic follow-up from the MVCC ordered-index key
semantics audit: typed NULL keys must be durable, searchable at the page-store
level, and safe through the Derby SQL bridge.

## Scope

This slice proves NULL and typed-key behavior only. It does not introduce a new
index format, does not change Derby optimizer authority, and does not weaken the
MVCC durable typed row codec.

## Required properties

* `DelosStorageOrderedIndexKey.encode(value)` remains the bridge boundary for
  Derby store values.
* SQL NULL values are encoded as the typed `DOK1|N|` ordered-index envelope.
* The durable ordered-index page store sorts the typed NULL envelope before
  typed non-NULL envelopes.
* The durable ordered-index page store can answer equality lookup for the typed
  NULL envelope.
* The durable ordered-index page store can range-scan across typed NULL and
  typed non-NULL bounds without interpreting raw legacy keys as typed keys.
* The SQL/MVCC bridge can commit, reopen, inspect, and query rows containing SQL
  NULL values without storing arbitrary Java objects.
* A committed table containing SQL NULL ordered-index keys remains consistent and
  supported non-NULL typed range lookups still use ordered pages.

## Non-goals

* Do not make `IS NULL` rely on a new optimizer path in this slice.
* Do not change Derby NULL comparison semantics.
* Do not resurrect candidate indexes as SQL authority.
* Do not weaken `MvccInheritedRowCodec` or allow arbitrary Java object storage.
* Do not change heap, raw-store, catalog, DRDA, or storage format behavior.

## Proofs

Low-level durable proof:

```text
MvccOrderedIndexPageStoreTest#typedNullEnvelopeSortsBeforeTypedValuesAndSupportsLookup
```

SQL bridge proof:

```text
MvccSqlTypedOrderedIndexKeyTest#testNullValuesKeepTypedOrderedIndexKeySemanticsThroughReopen
```

The SQL proof intentionally verifies SQL `IS NULL` behavior and durable ordered
index summaries separately. A future optimizer/path-selection slice may decide
whether `IS NULL` becomes an ordered-index shortcut. This slice only proves that
NULL keys are durable and safe.

## Verification

```bash
./gradlew delosMvccOrderedIndexNullKeyProofStaticAnalysis
./gradlew :delosdb-storage-mvcc:runMvccOrderedIndexPageStoreTest
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew s0CloseoutVerification
./gradlew :delosdb-storage-api:check :delosdb-storage-derby:check :delosdb-storage-mvcc:check
```
