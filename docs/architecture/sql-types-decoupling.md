# SQL types decoupling notes

Goal: make `org.apache.derby.iapi.types` a realistic future candidate for a
`delosdb-sql-types` module without creating a fake split.

## Current blockers

The SQL types package is conceptually a type-system block, but it previously
imported concrete engine implementation classes:

```text
org.apache.derby.impl.services.storetypes.EngineStoreRowLocationBridge
org.apache.derby.impl.sql.execute.DMLWriteResultSet
```

Those imports make `org.apache.derby.iapi.types` depend upward on engine
implementation details.

## This pass

This pass introduces two narrow contracts inside `org.apache.derby.iapi.types`:

```text
RowLocationServices
DeferredConstraintRecorder
```

Then engine implementation classes provide or implement those contracts:

```text
EngineRowLocationServices -> delegates to EngineStoreRowLocationBridge
DMLWriteResultSet         -> implements DeferredConstraintRecorder
```

After this pass, SQL type classes no longer import `org.apache.derby.impl.*`.
That does not yet make `delosdb-sql-types` possible, but it removes the most
obvious wrong direction.

## Still not solved

`org.apache.derby.iapi.types` still imports SQL/catalog/JDBC/internal packages
such as:

```text
org.apache.derby.iapi.sql.*
org.apache.derby.iapi.sql.conn.*
org.apache.derby.iapi.sql.dictionary.*
org.apache.derby.catalog.*
org.apache.derby.iapi.jdbc.*
```

Those are theoretical RDBMS boundaries that need classification before a real
physical module split.
