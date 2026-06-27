# SQL Types Remaining Dependency Map

MODULE19E source classification after MODULE19D.

MODULE19D removed the concrete implementation hooks from `org.apache.derby.iapi.types`:

```text
removed direct dependency on org.apache.derby.impl.services.storetypes.EngineStoreRowLocationBridge
removed direct dependency on org.apache.derby.impl.sql.execute.DMLWriteResultSet
```

That means the type package no longer imports concrete `org.apache.derby.impl.*`
classes.  This is necessary, but not sufficient, for a future
`delosdb-sql-types` module.

## Remaining non-runtime dependencies

### SQL context / execution dependencies

| File | Remaining dependency |
|---|---|
| `BooleanDataValue.java` | `Activation` |
| `DataTypeDescriptor.java` | `ConnectionUtil` |
| `NumberDataType.java` | `LanguageConnectionContext`, `DataDictionary` |
| `SQLBinary.java` | `StatementContext` |
| `SQLBoolean.java` | `Activation`, `Row`, `LanguageConnectionContext`, `ExecPreparedStatement` |
| `SQLChar.java` | `StatementContext` |
| `XML.java` | `ConnectionUtil` |

Interpretation: these are upward SQL-engine/session dependencies.  They block a
clean `delosdb-sql-types` module unless replaced by narrower type-environment
contracts or moved with a larger SQL API, which is not desirable yet.

### Database-context dependencies

| File | Remaining dependency |
|---|---|
| `ClobStreamHeaderGenerator.java` | `DatabaseContext` |
| `DataValueFactoryImpl.java` | `DatabaseContext` |
| `SQLChar.java` | `DatabaseContext` |
| `SQLDate.java` | `DatabaseContext` |
| `SQLTime.java` | `DatabaseContext` |
| `SQLTimestamp.java` | `DatabaseContext` |

Interpretation: these likely exist for locale, collation, DB properties, or
context lookup.  They are not concrete implementation hooks, but they still tie
SQL values to engine context.

### JDBC-facing stream descriptor dependencies

| File | Remaining dependency |
|---|---|
| `SQLChar.java` | `CharacterStreamDescriptor` |
| `SQLClob.java` | `CharacterStreamDescriptor` |
| `StringDataValue.java` | `CharacterStreamDescriptor` |

Interpretation: `CharacterStreamDescriptor` may be misplaced under
`org.apache.derby.iapi.jdbc`.  It looks like a type/LOB streaming descriptor and
may be a good future move candidate.

### Catalog/type descriptor dependencies

| File | Remaining dependency |
|---|---|
| `DataTypeDescriptor.java` | `TypeDescriptor`, `RowMultiSetImpl`, `TypeDescriptorImpl`, `UserDefinedTypeIdImpl` |
| `SQLRef.java` | `TypeDescriptor` |
| `TypeId.java` | `TypeDescriptor`, `BaseTypeIdImpl`, `DecimalTypeIdImpl`, `TypeDescriptorImpl`, `UserDefinedTypeIdImpl` |
| `UserType.java` | `TypeDescriptor` |
| `SQLBoolean.java` | `UUID` |
| `DeferredConstraintRecorder.java` | `UUID` |

Interpretation: catalog type descriptors are semantically close to SQL types,
but they currently live under `org.apache.derby.catalog` and
`org.apache.derby.catalog.types`.  A future split must decide whether these move
with `delosdb-sql-types` or belong to a separate catalog API.

### Storage type contracts

| File | Remaining dependency |
|---|---|
| `DataType.java` | `StoreDataType` |
| `DataValueDescriptor.java` | `StoreDataValue` |
| `DataValueFactory.java` | `StoreDataValueFactory` |
| `LocatedRow.java` | `StoreLocatedRow` |
| `Orderable.java` | `StoreOrderable` |
| `RefDataValue.java` | `StoreRefDataValue` |
| `RowLocation.java` | `StoreRowLocation` |
| `StringDataValue.java` | `StoreStringDataValue` |

Interpretation: these dependencies are acceptable if `delosdb-sql-types` depends
on `delosdb-storage-api`, because these are provider-neutral/store-facing
contracts, not concrete storage implementations.

## Split readiness

Current status:

```text
SQL types are cleaner after MODULE19D.
They are not yet ready to move into delosdb-sql-types.
```

Main blockers:

```text
1. SQL connection/session context dependencies
2. database context lookup dependencies
3. JDBC stream descriptor placement
4. catalog descriptor ownership decision
```

Next safe implementation pass:

```text
MODULE19F — isolate CharacterStreamDescriptor from iapi.jdbc
```

Why this one first: it is small, it appears in only three SQL type files, and it
looks like a type/LOB streaming concept rather than a JDBC engine concept.
