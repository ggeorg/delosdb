# MODULE19C — SQL types split source analysis

## Decision

Do not create `delosdb-sql-types` as a physical Gradle/JPMS module yet.

`org.apache.derby.iapi.types` is a real theoretical RDBMS building block, but the current source is not yet a clean module boundary. Moving it directly out of `delosdb-engine` would create an engine cycle or a fake placeholder module.

## Desired educational boundary

Conceptually, DelosDB should teach the SQL type/value subsystem as its own block:

```text
delosdb-runtime-api
  low-level runtime/service contracts

delosdb-sql-types
  SQL values, type descriptors, collation, conversion, row locations, SQL scalar values

delosdb-engine
  SQL compiler, optimizer, execution, catalog, JDBC, embedded engine
```

That is a good educational model, but the source must be decoupled before the physical module exists.

## Current package

Current source package:

```text
delosdb-engine/src/main/java/org/apache/derby/iapi/types
```

Current size:

```text
64 Java source files
```

Representative classes:

```text
DataValueDescriptor
DataValueFactory
DataValueFactoryImpl
DataTypeDescriptor
TypeId
SQLBoolean
SQLChar
SQLInteger
SQLDecimal
SQLDate
SQLTime
SQLTimestamp
SQLRef
RowLocation
StringDataValue
NumberDataValue
```

## Why it cannot move cleanly yet

The package is not only type contracts. It imports upward into the SQL engine, catalog, JDBC, and engine implementation code.

### SQL engine dependencies

Examples:

```text
org.apache.derby.iapi.sql.Activation
org.apache.derby.iapi.sql.Row
org.apache.derby.iapi.sql.conn.ConnectionUtil
org.apache.derby.iapi.sql.conn.LanguageConnectionContext
org.apache.derby.iapi.sql.conn.StatementContext
org.apache.derby.iapi.sql.dictionary.DataDictionary
org.apache.derby.iapi.sql.execute.ExecPreparedStatement
```

A clean `delosdb-sql-types` module should not require the full SQL engine. If it does, then `delosdb-engine` and `delosdb-sql-types` would depend on each other.

### Catalog dependencies

Examples:

```text
org.apache.derby.catalog.TypeDescriptor
org.apache.derby.catalog.UUID
org.apache.derby.catalog.types.BaseTypeIdImpl
org.apache.derby.catalog.types.DecimalTypeIdImpl
org.apache.derby.catalog.types.RowMultiSetImpl
org.apache.derby.catalog.types.TypeDescriptorImpl
org.apache.derby.catalog.types.UserDefinedTypeIdImpl
```

This means SQL types are entangled with catalog type descriptors. That may justify a later `delosdb-sql-catalog-api` or catalog descriptor split, but it should not be hidden inside `delosdb-sql-types` prematurely.

### JDBC dependency

Examples:

```text
org.apache.derby.iapi.jdbc.CharacterStreamDescriptor
```

Large object/string stream typing still reaches a JDBC-facing helper.

### Engine implementation dependencies

Examples:

```text
org.apache.derby.impl.services.storetypes.EngineStoreRowLocationBridge
org.apache.derby.impl.sql.execute.DMLWriteResultSet
```

These are hard blockers for a clean type module because they point from the type system into concrete engine implementation packages.

### Storage-facing dependencies

Examples:

```text
org.apache.derby.iapi.store.types.StoreDataType
org.apache.derby.iapi.store.types.StoreDataValue
org.apache.derby.iapi.store.types.StoreDataValueFactory
org.apache.derby.iapi.store.types.StoreRowLocation
org.apache.derby.iapi.store.types.StoreStringDataValue
```

These are acceptable as a direction only if `delosdb-sql-types` depends on `delosdb-storage-api` or a smaller store-type contract. This part is not the main blocker.

## Why not create an empty module now?

Do not create an empty `delosdb-sql-types` placeholder.

DelosDB is intended for education and research. Empty modules teach a false architecture. The module should appear only when it owns real source and has clean dependency direction.

## Clean split rule

Create `delosdb-sql-types` only when the package can satisfy this rule:

```text
delosdb-sql-types may depend on:
  delosdb-commons
  delosdb-runtime-api
  delosdb-storage-api or a narrower store-types contract
  Java platform modules

It must not depend on:
  delosdb-engine
  org.apache.derby.impl.sql.*
  org.apache.derby.impl.jdbc.*
  org.apache.derby.impl.services.storetypes.*
  SQL compiler/execution/catalog implementation packages
```

## Recommended preparation steps

### MODULE19C-1 — keep this analysis as the baseline

Document the blocker instead of creating a fake module.

### MODULE19C-2 — decouple engine implementation hooks from types

Replace direct type-system references to concrete engine implementation classes with a small contract/provider boundary.

Known hard blockers:

```text
DataValueFactoryImpl -> EngineStoreRowLocationBridge
SQLRef               -> EngineStoreRowLocationBridge
SQLBoolean           -> DMLWriteResultSet
```

### MODULE19C-3 — classify catalog/type descriptor ownership

Decide whether these remain in engine or move to a future catalog/type descriptor module:

```text
org.apache.derby.catalog.TypeDescriptor
org.apache.derby.catalog.types.*
```

### MODULE19C-4 — classify SQL iapi dependencies used by types

Before moving types, classify the SQL interfaces it uses:

```text
Activation
Row
ConnectionUtil
LanguageConnectionContext
StatementContext
DataDictionary
ExecPreparedStatement
```

### MODULE19C-5 — only then create `delosdb-sql-types`

The first real `delosdb-sql-types` overlay should move the full `org.apache.derby.iapi.types` package only after the dependency rule is satisfied.

## Current decision

`delosdb-sql-types` is the right conceptual module, but it is not safe as a physical module yet.

The next implementation overlay should not move the package. It should first remove or invert the concrete engine implementation dependencies from `org.apache.derby.iapi.types`.
