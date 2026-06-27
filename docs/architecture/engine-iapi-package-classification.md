# Engine `iapi` Package Classification

MODULE19E source classification.

## Purpose

DelosDB is intended to be useful for education and research.  Module names and
package ownership should therefore teach the major pieces of a relational
DBMS instead of hiding them behind inherited Derby names.

The package prefix `org.apache.derby.iapi` is misleading if it is read as
"public API".  In Derby it usually means **internal API**: contracts between
Derby subsystems.  Some of those contracts are good module boundaries.  Many
are not.

After retiring the empty `delosdb-engine-kernel` placeholder and renaming
`delosdb-engine-api` to `delosdb-runtime-api`, the honest baseline is:

```text
delosdb-runtime-api
  low-level runtime/service contracts

delosdb-engine
  embedded SQL engine implementation and internal SQL contracts

delosdb-derby-store-api
  inherited Derby store contracts

delosdb-storage-api
  Delos provider-neutral storage contracts
```

This document classifies the remaining `org.apache.derby.iapi.*` packages still
inside `delosdb-engine`.

## Current remaining engine `iapi` packages

Source count from the current source tree after MODULE19D:

| Package | Java files | Classification | Current decision |
|---|---:|---|---|
| `org.apache.derby.iapi.types` | 66 | SQL type/value subsystem | future `delosdb-sql-types` candidate, not ready |
| `org.apache.derby.iapi.sql.dictionary` | 56 | catalog/data dictionary contracts | stay in engine; future catalog split candidate |
| `org.apache.derby.iapi.jdbc` | 29 | embedded JDBC internal contracts | stay in engine |
| `org.apache.derby.iapi.sql.compile` | 27 | compiler/binder/optimizer contracts | stay in engine |
| `org.apache.derby.iapi.sql.execute` | 22 | execution engine contracts | stay in engine |
| `org.apache.derby.iapi.sql` | 13 | top-level SQL engine contracts | stay in engine |
| `org.apache.derby.iapi.db` | 7 | database boot/context contracts | stay in engine |
| `org.apache.derby.iapi.sql.conn` | 6 | language/session connection state | stay in engine |
| `org.apache.derby.iapi.sql.depend` | 6 | dependency tracking tied to catalog | stay in engine |
| `org.apache.derby.iapi.sql.execute.xplain` | 3 | execution-plan/XPLAIN contracts | stay in engine |
| `org.apache.derby.iapi.security` | 2 | SQL authorization helpers/contracts | stay in engine |
| `org.apache.derby.iapi.transaction` | 2 | transaction control/listener contracts | candidate for future runtime/transaction boundary |
| `org.apache.derby.iapi.services.jmx` | 1 | JMX management service contract | candidate for runtime-api, but not urgent |

## Educational RDBMS map

For teaching, the conceptual blocks should be shown this way:

```text
Client / tools / server
        |
        v
Embedded SQL engine
  - parser/compiler/optimizer
  - execution engine
  - catalog/data dictionary
  - SQL type/value system
  - JDBC/session/database boot
        |
        v
Storage contract boundary
  - Delos storage API
  - inherited Derby store API
        |
        v
Storage implementations
  - inherited Derby heap/raw/btree
  - native MVCC page/WAL/checkpoint
```

The physical modules do not need to match every conceptual block immediately.
For research and education, it is better to have an accurate package map than a
fake module split.

## Rules for moving an `iapi` package out of `delosdb-engine`

Move a package only if all of these are true:

1. It has a clear educational DBMS role.
2. It does not import concrete `org.apache.derby.impl.*` implementation classes.
3. It does not depend upward on SQL compiler/execution/catalog/JDBC internals,
   unless those dependencies are first replaced with narrower contracts.
4. More than one module genuinely needs it as a contract boundary.
5. The resulting dependency direction remains acyclic.

The desired direction for a future SQL types module is:

```text
delosdb-runtime-api
        ↑
delosdb-sql-types
        ↑
delosdb-engine
```

The unacceptable direction is:

```text
delosdb-sql-types -> delosdb-engine
```

That would be a fake split.

## Current decisions

### Keep in `delosdb-engine`

These are internal SQL engine contracts and should stay in the engine for now:

```text
org.apache.derby.iapi.sql
org.apache.derby.iapi.sql.compile
org.apache.derby.iapi.sql.conn
org.apache.derby.iapi.sql.depend
org.apache.derby.iapi.sql.dictionary
org.apache.derby.iapi.sql.execute
org.apache.derby.iapi.sql.execute.xplain
org.apache.derby.iapi.jdbc
org.apache.derby.iapi.db
org.apache.derby.iapi.security
```

Reason: moving them now would mostly create an enormous `engine-api`-like module
again, just under a different name.

### Candidate for future `delosdb-sql-types`

```text
org.apache.derby.iapi.types
```

Reason: SQL values and type descriptors are a real RDBMS teaching block.
However, the package still has upward dependencies on SQL/catalog/JDBC context.
MODULE19D removed the concrete `org.apache.derby.impl.*` hooks, but that is only
one step.

### Candidate for later runtime/transaction boundary

```text
org.apache.derby.iapi.transaction
org.apache.derby.iapi.services.jmx
```

Reason: these are small and less obviously SQL-engine-specific.  Do not move
them in the SQL types sequence.  They can be studied later.

## Recommended next work

Do not create `delosdb-sql-types` yet.

Next passes should reduce the remaining upward dependencies from
`org.apache.derby.iapi.types`:

1. Isolate `CharacterStreamDescriptor` from `iapi.jdbc` if it is really a type
   streaming descriptor rather than a JDBC contract.
2. Replace direct use of `DatabaseContext`/`ConnectionUtil`/`StatementContext`
   with a narrower type-environment contract if source proves it is safe.
3. Study catalog descriptor dependencies (`TypeDescriptor`, `TypeDescriptorImpl`,
   `BaseTypeIdImpl`, `UserDefinedTypeIdImpl`, `RowMultiSetImpl`) before deciding
   whether they move with SQL types or belong to a future catalog API.
4. Leave compiler/execution/catalog modules unsplit until the SQL type boundary
   is clean.
