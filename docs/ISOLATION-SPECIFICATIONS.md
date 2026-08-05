# DelosDB isolation specifications

DelosDB isolation specifications are declarative, DelosDB-owned concurrency tests modeled on the
methodology of PostgreSQL's `src/test/isolation` suite. PostgreSQL is a scenario and scheduling
reference only. DelosDB does not copy or depend on PostgreSQL's parser, runner, SQL, or expected files.

## Authority

The permanent inventory is:

```text
gradle/testing/delos-isolation-specifications.tsv
```

External-methodology provenance is:

```text
gradle/testing/delos-external-test-provenance.tsv
```

Every case records the PostgreSQL archive fingerprint, source scenario, license, adaptation type,
semantic intent, DelosDB changes, and applicable providers.

## Format

Each JSON specification declares:

```text
id and description
category
providers: heap and/or mvcc
storages: file and/or memory
connections: embedded
setup and teardown SQL
named sessions
named steps
explicit permutations
final-state query assertions, optionally scoped by provider and storage
```

A session may set a default JDBC isolation level and provider-specific overrides. Step actions are:

```text
SQL
COMMIT
ROLLBACK
SAVEPOINT
ROLLBACK_TO_SAVEPOINT
RELEASE_SAVEPOINT
```

SQL steps may declare:

```text
rows
updateCount
sqlStates
successAllowed
```

`sqlStates` lists accepted failures. When `successAllowed` is `false`, one of those SQLStates is
required. When it is `true`, either a successful result matching the declared rows/update count or an
accepted SQLState is valid.

Permutation operations are:

```text
RUN
START
ASSERT_BLOCKED
AWAIT
DRAIN_AND_COMMIT
```

`START` launches a named step asynchronously. `ASSERT_BLOCKED` succeeds only after Derby exposes a
`WAIT` entry through `SYSCS_DIAG.LOCK_TABLE`; a merely incomplete future is not proof of blocking.
`AWAIT` collects one asynchronous result. `DRAIN_AND_COMMIT` resolves a multi-session deadlock by
committing sessions as their pending steps complete, allowing the remaining survivors to progress.

A permutation may declare exact SQLState counts:

```json
"sqlStateAssertions": [
  { "sqlState": "40001", "minimum": 1, "maximum": 1 }
]
```

Deadlock cases use this to require an exact provider-specific outcome bound without assuming which
session Derby will choose. A three-session MVCC cycle may yield one lock-manager victim followed by one
stale-snapshot write-conflict victim; both use SQLState `40001` and the final-state assertion proves the
durable survivor result.

Foreign-key schedules distinguish a statement attempt from a transaction-level retry. In
`DEL-FK-002`, the heap attempt waits for the parent transaction and succeeds when that delete rolls
back, while MVCC rejects the stale attempt with `23503`. Both paths then roll back the child
transaction, execute a second named retry step, and commit the valid child row. Crossed parent deletes
form a heavyweight-lock deadlock on heap, while MVCC rejects both references immediately and both
deleting transactions roll back.

DROP and TRUNCATE schedules remain provider-specific because MVCC readers do not retain heap-style
statement locks. CREATE INDEX is different: every access method must serialize the index build with
active writers so the initial backfill and future index maintenance form one correct publication
boundary. `DEL-DDL-002` proves the wait and then forces the new index for the writer's committed key.

## Stage 4 catalogue

```text
DEL-ISO-001..004       snapshot stability and documented read anomaly
DEL-ISO-010..012       savepoint rollback
DEL-DEADLOCK-001..004  two-row, conversion, three-session and survivor consistency
DEL-TRAVERSAL-001..004 update/delete traversal, key movement and row identity
DEL-FK-001..004        referential-integrity contention, rollback, deadlock and snapshot
DEL-DDL-001..004       DROP, CREATE INDEX, TRUNCATE and trigger lifecycle conflicts
DEL-MERGE-001..002     conflicting and disjoint concurrent MERGE
```

The production RawStore MVCC scan boundary consumes Derby's store isolation level: READ COMMITTED
and weaker scans acquire a fresh statement snapshot lease, while REPEATABLE READ retains the
transaction snapshot. Provider-specific permutations capture legitimate heap-locking and MVCC
first-committer-wins differences without weakening final-state invariants.

The catalogue contains 25 stable case IDs. The static gate rejects missing cases, additional
uninventoried cases, missing categories, stale resources, incomplete provenance, invalid operation
references, unawaited asynchronous tokens, and registry/authority drift.

## Execution

```bash
./gradlew \
  :delosdb-tests:delosIsolationSpecStaticAnalysis \
  :delosdb-tests:runDelosIsolationSpecificationTests \
  --console=plain
```

Generated reports:

```text
delosdb-tests/build/reports/tests/delosdb-isolation-specifications.json
delosdb-tests/build/reports/tests/delosdb-isolation-specifications.txt
```

The test class is also part of `delosConcurrencyTests`, root `check`, `fullVerification`, nightly, and
release verification through the stable suite graph.

## Connection scope

Stage 4 uses embedded connections so the runner can observe Derby's heavyweight lock table directly
and avoid embedding network-server lifecycle management in the concurrency scheduler. DRDA transport,
disconnect, cancellation, restart, and streaming equivalence remain covered by the established DelosDB
system and failure-path suites.

## Authoring rules

- Use explicit named permutations for every blocking scenario.
- Bound every asynchronous wait.
- Assert observable SQL behavior and final database state, not implementation internals.
- Use provider-specific permutations only where heap locking and MVCC visibility differ.
- Require exact provider-specific SQLState counts or bounded victim ranges for deadlocks.
- Preserve a stable case ID when minimizing a regression.
- Add provenance before adding a resource.
- Do not copy PostgreSQL SQL or expected output wholesale.
