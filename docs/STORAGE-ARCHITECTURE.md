# DelosDB Storage Architecture

DelosDB keeps Derby compatibility at the SQL, JDBC, DRDA, catalog, heap, and raw-store boundaries while adding DelosDB-owned storage seams behind explicit opt-in paths.

This document describes the storage architecture after the current storage closeout. It is intended for contributors who need to understand where compatibility is preserved, where DelosDB behavior is allowed to evolve, and which verification gates protect those boundaries.

## Architecture rule

```text
Preserve Derby compatibility at public and durable boundaries.
Modernize internals behind explicit, verified seams.
Do not make broad rewrites where a narrow compatibility-preserving path exists.
```

The storage architecture is therefore not a wholesale Derby replacement. It is a compatibility-preserving fork with a growing DelosDB storage layer.

## Storage modes

### Derby-compatible heap mode

The inherited Derby heap/raw-store path remains the default:

```sql
CREATE TABLE t (id int primary key, value varchar(100));
```

This mode preserves Derby heap page format, raw-store logging behavior, catalog behavior, JDBC behavior, and DRDA compatibility. DelosDB may improve diagnostics and verification around this path, but it must not silently change the durable heap format.

### Opt-in `delos_mvcc` mode

The MVCC engine is explicit:

```sql
CREATE TABLE t (id int primary key, value varchar(100)) USING delos_mvcc;
```

The active path is:

```text
SQL
  -> Derby language / transaction layer
  -> Derby access-method bridge
  -> delosdb-storage-api
  -> delosdb-storage-mvcc
```

The MVCC engine is allowed to use DelosDB-owned durable structures, page metadata, indexes, diagnostics, and consistency checks. It must still preserve the SQL/JDBC/DRDA compatibility surface that invokes it.

## Compatibility boundaries

The following boundaries are intentionally protected:

```text
Derby heap page format
Derby raw log format
Derby catalog semantics
Derby JDBC behavior
Derby DRDA wire compatibility
Default heap-backed table behavior
Derby optimizer fallback and remainder-predicate evaluation
```

The following boundaries are DelosDB-owned seams:

```text
delos_mvcc durable row and page formats
DelosDB storage provider discovery
delosdb-storage-api diagnostics
MVCC ordered-index sidecars
MVCC vacuum/compress lifecycle
MVCC page cache and reusable-page tracking
Cross-engine consistency report shape
```

## Heap consistency checking

`SYSCS_UTIL.SYSCS_CHECK_TABLE(...)` now reaches real heap checking for Derby-compatible heap tables:

```text
SYSCS_UTIL.SYSCS_CHECK_TABLE
  -> ConsistencyChecker.checkTable
  -> ConglomerateController.checkConsistency
  -> OpenHeap.checkConsistency
  -> HeapSanityChecker
```

The heap checker is read-only. It validates healthy heap pages, page traversal, page accounting, record counts, and slot-table invariants. It reports failures through Derby exceptions rather than stdout, stderr, or diagnostic `PrintStream` output.

The checker assumes the existing Derby consistency-check locking discipline. It is not a live unlocked corruption detector and should not be reused as a background repair worker.

## MVCC isolation read-view policy

The bridge exposes an explicit isolation policy for MVCC scans:

```text
READ COMMITTED and weaker
  fresh statement-scoped read view

REPEATABLE READ
  transaction-scoped stable read view

SERIALIZABLE
  transaction-scoped stable read view for Derby/JDBC compatibility
  no full-serializability guarantee for delos_mvcc
```

The policy is intentionally documented in storage-bridge code rather than
hidden as a private condition inside scan logic. SQL integration tests verify
statement refresh for `READ COMMITTED`, stable visibility for `REPEATABLE READ`,
read-your-writes behavior, historical page-backed snapshot use, and the current
`SERIALIZABLE` write-skew limitation.

`delos_mvcc` currently has no predicate or range locking, SSI dangerous-
structure detection, or serialization-failure protocol. Full serializability
must be implemented deliberately in a later phase or exposed as unsupported;
it must not be inferred from the current JDBC isolation name.

## Object deserialization boundary

Derby heap compatibility can still read Derby heap `JAVA_OBJECT` values in default mode. DelosDB also provides an opt-in heap object deserialization filter:

```text
delosdb.heap.objectDeserializationFilter
```

Unset or blank preserves Derby-compatible behavior. When configured, the filter is installed once through the central heap object stream path. Static gates prevent duplicate installation and prevent the filter from drifting into unrelated serialization infrastructure.

`delos_mvcc` keeps a stricter durable-row boundary and rejects Java object rows. That is separate from heap compatibility mode.

## Cross-engine consistency reporting

The storage diagnostics layer exposes a provider-neutral consistency report. Its job is to surface findings from heap, B-tree, MVCC, and future storage modes using a stable report shape.

The framework is diagnostic only:

```text
read-only
provider-neutral
no repair
no cleanup
no stdout/stderr side effects
```

It is intended to help tooling and SQL diagnostics reason across mixed heap and MVCC databases without making one storage engine responsible for another engine's state.

## Runtime provider discovery

The `delos_mvcc` engine is discovered through DelosDB storage provider service metadata. The runtime artifact model now treats provider jars as part of the verified runtime, not as incidental build outputs.

The provider gate verifies that:

```text
MVCC runtime jar exists
service metadata exists
provider class is listed
ServiceLoader can discover providerName() == "delos_mvcc"
```

This prevents the integration runtime from compiling successfully while losing the MVCC provider jar at execution time.

## Verification gates

Focused storage/runtime verification:

```sh
./gradlew verifyDelosRuntimeStorageProviders
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew s0CloseoutVerification
./gradlew :delosdb-storage-mvcc:check
./gradlew :delosdb-storage-api:check :delosdb-storage-derby:check :delosdb-storage-bridge:check :delosdb-storage-mvcc:check
```

Important S0 static gates now protect:

```text
heap/raw-store stdout and stale-code rules
heap compatibility behavior
heap object deserialization filter placement
runtime artifact/provider model
cross-engine consistency framework shape
server compatibility seams
storage static analysis
```

## Design anti-goals

The current storage architecture does not authorize:

```text
changing Derby heap page format
changing Derby raw log format
replacing DRDA/JDBC wire compatibility
flipping default storage to MVCC
removing Derby optimizer fallback behavior
removing Derby remainder-predicate evaluation
adding repair behavior to consistency diagnostics
using Java serialization in MVCC durable rows
hiding runtime provider discovery behind unverified jar lists
```
