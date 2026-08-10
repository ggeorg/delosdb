# Readable engine: EXPLAIN and EXPLAIN ANALYZE

DelosDB exposes the optimizer decision and bounded execution evidence through one stable diagnostic
model. The readable-engine surface is designed to explain the production compiler and executor, not
to create a second optimizer, runtime plan tree, or profiling subsystem.

## SQL surface

`EXPLAIN <statement>` compiles the target normally through parse, bind, and optimize, but does not
execute the target. It returns one row with two CLOB columns:

```text
PLAN_TEXT
PLAN_JSON
```

The text and JSON forms are rendered from the same immutable schema-version-1 `StablePlanModel`.
DML and DDL can therefore be explained without applying their side effects. Parse, bind, and optimize
failures retain their normal SQLState.

`EXPLAIN ANALYZE <query>` executes the already-bound and optimized query once, consumes its rows, and
returns the same two CLOB columns with a stable plan plus schema-version-6 execution evidence. The
current ANALYZE contract is query-only. INSERT, UPDATE, DELETE, MERGE, CALL, DDL, and updatable SELECT
ANALYZE remain unsupported with SQLState `0A000` until their side-effect contract is designed
explicitly.

## What the stable plan contains

Each stable node has deterministic pre-order identity and can expose:

```text
logical and physical operation
relation and storage mode
selected base-table or index access path
join strategy
estimated rows and cost
predicate placement
ordering
selected-plan decision reason
```

The plan is bounded to 512 nodes. Unsupported compiler shapes fail closed to the stable generic
vocabulary instead of serializing compiler implementation classes.

## What EXPLAIN ANALYZE adds

Execution evidence is correlated back to stable node ids through Derby's generated `resultSetNumber`.
The executor never reconstructs a plan from runtime Java class names.

An observed node can expose:

```text
opens
estimatedRows
actualRows
estimateComparison
rowsSeen
rowsFiltered
elapsedMillis
openMillis
nextMillis
closeMillis
storageMetrics
```

`actualRows` is nullable. A number means rows produced by that operator; DelosDB does not substitute
input-row counters where Derby has no authoritative output-row count.

`estimateComparison` is one of:

```text
MATCH
UNDER_ESTIMATE
OVER_ESTIMATE
UNKNOWN
```

`UNDER_ESTIMATE` means actual output exceeded the optimizer estimate. `OVER_ESTIMATE` means the
estimate exceeded actual output. No ratio, tolerance, or severity threshold is invented.

For MVCC scans the evidence can additionally expose:

```text
mvccSnapshotSequence
mvccReadPath
mvccVersionTraversal
```

`mvccReadPath` is derived from the stable physical operation plus existing ordered-index counters and
can be `TABLE_SCAN`, `NO_CANDIDATES`, `COVERED`, `FALLBACK`, `MIXED`, or `UNKNOWN`.

`mvccVersionTraversal` can be `NOT_MEASURED`, `NONE`, `HEAD_ONLY`, `HISTORICAL`, or `UNKNOWN`.
Plain MVCC table scans are `NOT_MEASURED` for version traversal because the current ordered-index
counters do not cover that path. DelosDB does not report zero work when the engine did not measure it.

`mvccSnapshotSequence` is the exact snapshot sequence already owned by the executing scan. It is
execution-specific, like timing, and is not reconstructed later from global MVCC state.

## One operation end to end

The repository demonstration executes this query against a `delos_mvcc` table:

```sql
select id, amount from readable_order
    --DERBY-PROPERTIES index=readable_order_status_idx
    where status = 'OPEN'
    order by id
```

Run it with:

```bash
./gradlew readableEngineDemo --console=plain
```

The task uses the assembled runtime jars and `ij`, creates a disposable database under
`build/readable-engine-demo`, runs both `EXPLAIN` and `EXPLAIN ANALYZE`, and removes the database on
the next run. The SQL lives in [`../examples/readable-engine.sql`](../examples/readable-engine.sql)
and is also included in the binary distribution.

### 1. JDBC/DRDA entry and statement compilation

Embedded JDBC and DRDA both reach the normal Derby statement compiler. `GenericStatement.prepMinion()`
in `delosdb-engine/src/main/java/org/apache/derby/impl/sql/GenericStatement.java` owns the compile
pipeline:

```text
SQL text
  -> parser.parseStatement(...)
  -> StatementNode.bindStatement()
  -> StatementNode.optimizeStatement()
  -> buildStablePlanModel(...)
  -> StatementNode.generate(...)
```

`EXPLAIN` is represented by `ExplainNode`. Its target statement is bound and optimized through the
same target nodes as a direct statement. There is no alternate optimizer path for diagnostics.

### 2. Stable selected-plan capture

After optimization and before generated activation construction, `StatementNode.buildStablePlanModel()`
uses `StablePlanModelBuilder` to copy the selected plan into immutable diagnostic state. For ANALYZE,
`buildStablePlanResultSetNumbers()` captures the generated result-set identities in the same traversal
order.

`GenericPreparedStatement` retains both values. The stable model does not keep compiler-node
references and does not participate in execution.

### 3. Generated activation and result-set tree

The explained query still generates its normal activation/result-set tree through
`StatementNode.generate()`, `JavaFactory`, `ClassBuilder`, and the JDK 25 `ClassFileJava` backend.
`ExplainAnalyzeResultSet` wraps that real query result set. It does not interpret the stable plan to
execute the query.

### 4. Query execution and MVCC storage ownership

`ExplainAnalyzeResultSet.openCore()` opens the source and repeatedly calls `getNextRowCore()` until the
query is exhausted. The selected scan reaches the normal heap or MVCC access method.

For MVCC, `MvccRawStoreScanController` owns the scan snapshot and uses that same snapshot sequence for
visibility decisions. Its `getScanInfo()` creates immutable `MvccScanInfo`, including already-maintained
scan and ordered-read counters plus the snapshot sequence. No second snapshot is acquired for ANALYZE.

### 5. Immutable execution evidence

Before scans lose their authoritative state, the ANALYZE result set asks Derby's existing runtime
statistics machinery to materialize scan information. It then closes the source tree and obtains the
final statistics snapshot so open/next/close timing is complete.

`StablePlanExecutionEvidenceBuilder` correlates runtime statistics to stable plan nodes through the
captured result-set numbers and builds immutable, bounded `StablePlanExecutionEvidence`.

### 6. One renderer authority

`StablePlanExecutionRenderer` receives the retained stable plan and correlated execution evidence.
It derives text and JSON from those same two immutable values. Storage summaries and estimate
comparisons are renderer-time derivations; they do not feed back into optimizer or executor state.

The final row is returned through the normal JDBC/DRDA result path as `PLAN_TEXT` and `PLAN_JSON` CLOBs.

## Determinism and execution-specific values

Ordinary `EXPLAIN` is deterministic for the same selected plan and has embedded/DRDA byte parity.
`EXPLAIN ANALYZE` intentionally contains values that can differ between executions:

```text
elapsedMillis
openMillis
nextMillis
closeMillis
mvccSnapshotSequence
```

Compatibility tests normalize only those execution-specific values. Plan structure, node identity,
actual rows, estimate classification, storage counters, and derived MVCC read-path diagnostics remain
part of deterministic transport parity.

## Cost model

Normal execution does not build stable execution evidence. ANALYZE reuses existing Derby result-set
counters and scan information. Analyze-only timing is enabled when the result-set objects are
constructed; no additional timer or callback is inserted into ordinary row loops.

The Phase 10 closeout deliberately does not add MVCC table-scan version-traversal instrumentation only
for diagnostics. Missing evidence is represented as `NOT_MEASURED` or `UNKNOWN` rather than paid for
on the hot path.

## Source map

| Responsibility | Production authority |
|---|---|
| compile orchestration | `GenericStatement.prepMinion()` |
| EXPLAIN target ownership | `ExplainNode` |
| stable plan construction | `StablePlanModelBuilder` |
| plan retention | `GenericPreparedStatement` |
| generated activation/result-set construction | `StatementNode.generate()` and JDK 25 `ClassFileJava` |
| ANALYZE execution wrapper | `ExplainAnalyzeResultSet` |
| runtime statistics snapshot | Derby `ResultSetStatistics` hierarchy |
| MVCC scan evidence | `MvccRawStoreScanController.getScanInfo()` / `MvccScanInfo` |
| stable correlation | `StablePlanExecutionEvidenceBuilder` |
| text/JSON rendering | `StablePlanRenderer` / `StablePlanExecutionRenderer` |

## Verification

The permanent focused contracts are:

```bash
./gradlew \
  :delosdb-tests:delosFunctionalTests \
  --tests 'org.apache.derbyTesting.functionTests.tests.delos.ExplainTest' \
  --console=plain

./gradlew \
  :delosdb-tests:delosSystemTests \
  --tests 'org.apache.derbyTesting.functionTests.tests.delos.ExplainCompatibilityTest' \
  --console=plain
```

The demonstration is teaching material, not an additional semantic authority. The tests above own the
format, execution, side-effect boundary, operator-cardinality, storage-evidence, and transport-parity
contracts.
