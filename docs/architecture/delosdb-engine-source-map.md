# DelosDB engine source map

`delosdb-engine` is currently the inherited Derby SQL engine plus DelosDB integration code.  It
should remain one physical Gradle module until source dependencies prove that a smaller project
layout is cleaner.  This document maps the current engine source to modern RDBMS building blocks so
students and researchers can understand the system before it is physically reorganized.

## Engine building blocks

| RDBMS building block | Current source area |
|---|---|
| SQL entry / embedded JDBC | `org.apache.derby.impl.jdbc`, `org.apache.derby.iapi.jdbc`, `org.apache.derby.impl.db` |
| Parser, binder, compiler, optimizer | `org.apache.derby.impl.sql.compile`, `org.apache.derby.iapi.sql.compile` |
| SQL execution engine | `org.apache.derby.impl.sql.execute`, `org.apache.derby.iapi.sql.execute` |
| Runtime statistics and XPLAIN | `org.apache.derby.impl.sql.execute.rts`, `org.apache.derby.iapi.sql.execute.xplain` |
| Catalog / data dictionary | `org.apache.derby.impl.sql.catalog`, `org.apache.derby.iapi.sql.dictionary`, `org.apache.derby.catalog` |
| SQL value and type system | `org.apache.derby.iapi.types` |
| Session and language connection state | `org.apache.derby.iapi.sql.conn` |
| Dependency management | `org.apache.derby.iapi.sql.depend` |
| Transaction boundary | `org.apache.derby.iapi.transaction` |
| Engine runtime implementations | `org.apache.derby.impl.services.*` |
| Runtime/service contracts | `delosdb-runtime-api`, inherited `org.apache.derby.iapi.services.*` |
| Storage integration boundary | `org.apache.derby.impl.services.storetypes`, storage bridge, storage APIs |

## Runtime API policy

`delosdb-runtime-api` should contain low-level runtime/service contracts, not every inherited Derby
`iapi` package.  The current policy is:

```text
Belongs in delosdb-runtime-api:
  org.apache.derby.iapi.services.*
  org.apache.derby.iapi.util
  org.apache.derby.iapi.xml
  org.apache.derby.io

Stays in delosdb-engine for now:
  org.apache.derby.iapi.sql.*
  org.apache.derby.iapi.jdbc
  org.apache.derby.iapi.db
  org.apache.derby.iapi.security
  org.apache.derby.iapi.transaction
  org.apache.derby.iapi.types
  org.apache.derby.iapi.services.jmx
```

The purpose of `runtime-api` is to expose shared inherited runtime contracts such as context,
monitor, lock, cache, daemon, timer, UUID, service loading, and runtime I/O contracts. It should not
become a new large `engine-api` module and should not become the general home for DelosDB-owned
storage, SPI, model, or diagnostic contracts. The Phase 23 contract ownership map and boundary audit
own that decision.

## SQL types status

`org.apache.derby.iapi.types` is conceptually a strong future candidate for a `delosdb-sql-types`
area because SQL values, comparisons, conversions, null semantics, and LOB/string/binary handling
are real RDBMS concepts.

It should stay in `delosdb-engine` for now because it still has dependencies on SQL context,
catalog, and JDBC concepts.  The first decoupling pass removed concrete implementation hooks, but a
clean SQL types project layout still requires further source work.

Target dependency direction for a future SQL types module:

```text
delosdb-runtime-api
        ↑
delosdb-sql-types
        ↑
delosdb-engine
```

Unacceptable direction:

```text
delosdb-sql-types -> delosdb-engine
```

That would be a fake split.

## Rules for future engine layout changes

A package or module move is justified only when all of the following are true:

```text
1. The boundary represents a real modern RDBMS subsystem.
2. The source dependency direction supports the move.
3. The new location contains real code, not a placeholder.
4. The move improves education or research without hiding inherited Derby behavior.
5. The build remains understandable and does not require excessive patch-module/add-exports work.
```

For now, the preferred approach is:

```text
1. Keep inherited Derby SQL engine packages traceable.
2. Add DelosDB-owned modern RDBMS model, trace, and diagnostics packages inside delosdb-engine.
3. Prove the model against real execution paths.
4. Use the proven model to decide the future project layout.
```


## DelosDB-owned engine model boundary

The intended DelosDB-owned engine package shape is:

```text
io.github.ggeorg.delosdb.engine.model
  RDBMS building-block vocabulary and small contracts

io.github.ggeorg.delosdb.engine.trace
  trace events, sinks, registry, and Derby execution hook helpers

io.github.ggeorg.delosdb.engine.diagnostics
  formatter, summary, observed-plan reports, and other reader-facing diagnostic views
```

Derby code remains in `org.apache.derby.*` packages and adapts to or emits evidence against this
model. Calcite-inspired experiments or student proofs should also adapt to this model rather than
inventing parallel terminology. Trace records evidence; it is not the model itself.
