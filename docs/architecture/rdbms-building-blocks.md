# DelosDB RDBMS building blocks

This document is a source map for DelosDB as an educational and research
RDBMS. It describes conceptual building blocks first, and only then maps them
to current Gradle modules and Java packages.

## Current module baseline

After retiring the empty engine-kernel placeholder and renaming engine-api, the
current dependency report shows an honest baseline:

```text
Modules: 22
Unresolved project imports: 0
```

The major runtime-facing modules are now:

```text
delosdb-runtime-api       low-level runtime/service contracts
delosdb-engine            embedded SQL engine implementation
delosdb-storage-api       provider-neutral Delos storage contracts
delosdb-derby-store-api   inherited Derby store contracts
delosdb-storage-derby     inherited Derby heap/raw/btree implementation
delosdb-storage-mvcc      native MVCC implementation
delosdb-storage-bridge    temporary Derby access-method adapter
```

## Theoretical RDBMS blocks

A relational database can be taught as these cooperating blocks:

```text
SQL/API front door
  parser
  binder
  optimizer
  execution engine
  type system
  catalog / data dictionary
  transaction/runtime services
  storage access API
  storage providers
  diagnostics/tools/tests
```

## Current source map

| Building block | Current package/module home | Physical module status |
|---|---|---|
| Runtime contracts | `delosdb-runtime-api`, `org.apache.derby.iapi.services.*`, `org.apache.derby.io` | Clean module |
| SQL parser/compiler/optimizer | `delosdb-engine`, `org.apache.derby.impl.sql.compile`, `org.apache.derby.iapi.sql.compile` | Conceptual only |
| SQL execution | `delosdb-engine`, `org.apache.derby.impl.sql.execute`, `org.apache.derby.iapi.sql.execute` | Conceptual only |
| Catalog/data dictionary | `delosdb-engine`, `org.apache.derby.impl.sql.catalog`, `org.apache.derby.iapi.sql.dictionary`, `org.apache.derby.catalog.*` | Conceptual only |
| SQL values/type system | `delosdb-engine`, `org.apache.derby.iapi.types` | Candidate future module |
| Embedded JDBC | `delosdb-engine`, `org.apache.derby.impl.jdbc`, `org.apache.derby.iapi.jdbc` | Conceptual only |
| Inherited Derby store API | `delosdb-derby-store-api`, `org.apache.derby.iapi.store.*` | Clean module |
| Delos storage API | `delosdb-storage-api`, `org.apache.derby.iapi.store.types.*` Delos contracts | Clean module |
| Derby storage provider | `delosdb-storage-derby`, `org.apache.derby.impl.store.*` | Clean provider module |
| MVCC storage provider | `delosdb-storage-mvcc`, `io.github.ggeorg.delosdb.storage.mvcc.*` | Clean provider module |
| MVCC Derby adapter | `delosdb-storage-bridge`, `org.apache.derby.impl.store.access.mvcc.*` | Temporary adapter |
| Storeless mode | `delosdb-storeless` | Compatibility/no-real-store module |

## Education/research rule

Prefer modules that teach real architectural concepts. Avoid empty placeholder
modules. A new physical module is justified only when it owns real source code
and has a clear dependency direction.

A future physical split should satisfy:

```text
new module -> runtime-api / commons / storage-api / derby-store-api
new module must not depend back on delosdb-engine
```

## SQL types status

`org.apache.derby.iapi.types` is the best next candidate for a teaching-focused
subsystem because it represents SQL values, type descriptors, comparison,
normalization, collation, null handling, and JDBC value materialization.

But it is not ready to become `delosdb-sql-types` until concrete engine
implementation hooks are removed. The first decoupling pass removes direct
imports from SQL types to `org.apache.derby.impl.*` classes and replaces them
with narrow contracts.
