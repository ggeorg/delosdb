package io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.VersionedStorageProviderDiscovery;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.execution.VersionedStorageExecutionBridge;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.dictionary.ColumnDescriptor;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosMutationResult;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.types.DataTypeDescriptor;
import org.apache.derby.impl.services.storetypes.EngineMvccTableAccess;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Proof-only native provider-owned table registry for retired Delos ResultSet
 * paths and explicit provider diagnostics.
 *
 * <p>This is not a SQL router. It is the catalog-to-provider boundary used by
 * remaining opt-in diagnostics after Derby has parsed, bound, and selected a
 * {@code delos_mvcc} table via {@link TableDescriptor} metadata.</p>
 *
 * <p><strong>MODULE10A bridge status:</strong> no normal
 * {@code CREATE TABLE ... USING delos_mvcc} or Derby commit/rollback path should
 * populate or complete this registry anymore. Normal MVCC table state and
 * transaction outcomes are owned by the inherited store/access provider through
 * {@code MvccConglomerate} and {@code MvccStoreAccessTransactionRegistry}. Keep
 * this class only for proof-only provider diagnostics until those callers are
 * retired.</p>
 */
@InternalApi
public final class DelosNativeTableRegistry {
    private static final String PROVIDER_NAME = "delos_mvcc";
    private static final Object LOCK = new Object();
    private static final String DEFAULT_DATABASE_SCOPE = "__delosdb_default__";
    private static final Map<NativeTableKey, TableDefinition> TABLES = new HashMap<>();
    private static final Map<Object, TransactionScope> DERBY_TRANSACTION_SCOPES = new IdentityHashMap<>();
    private static final Map<String, VersionedStorageProvider> CACHED_PROVIDERS = new HashMap<>();
    private static final VersionedStorageExecutionBridge TABLE_OPERATION_BRIDGE =
            VersionedStorageExecutionBridge.resolvedTableOperations();

    private DelosNativeTableRegistry() {
    }

    /**
     * Registers provider-owned storage from Derby's CREATE TABLE constant action.
     * The caller has already resolved schema/table metadata and persisted the
     * TableDescriptor; this method does not parse or route SQL text.
     */
    public static void registerNativeExecutionTable(
            LanguageConnectionContext languageConnectionContext,
            String schemaName,
            String tableName,
            List<String> columnNames,
            List<String> typeNames) throws SQLException {
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(tableName, "tableName");
        Objects.requireNonNull(columnNames, "columnNames");
        Objects.requireNonNull(typeNames, "typeNames");
        if (columnNames.size() != typeNames.size()) {
            throw sqlException("X0MV6", "Native delos_mvcc table registration column metadata mismatch for "
                    + schemaName + "." + tableName + ": " + columnNames.size() + " names but "
                    + typeNames.size() + " type names");
        }
        if (columnNames.isEmpty()) {
            throw sqlException("42X14", "CREATE TABLE USING delos_mvcc requires at least one column");
        }

        String scopeKey = databaseScope(languageConnectionContext);
        VersionedTableMetadata metadata = metadata(schemaName, tableName);
        NativeTableKey tableKey = new NativeTableKey(scopeKey, metadata);
        List<ColumnDefinition> columns = new ArrayList<>(columnNames.size());
        for (int i = 0; i < columnNames.size(); i++) {
            columns.add(ColumnDefinition.parse(columnNames.get(i), typeNames.get(i)));
        }

        synchronized (LOCK) {
            if (TABLES.containsKey(tableKey)) {
                throw sqlException("X0Y32", "Versioned storage table already exists: "
                        + metadata.qualifiedName());
            }
            VersionedStorageProvider provider = provider(scopeKey);
            VersionedTable<Long, List<Object>> table = TABLE_OPERATION_BRIDGE.createTable(provider, metadata);
            TABLES.put(tableKey, new TableDefinition(metadata, columns, table, provider.transactionCoordinator()));
        }
    }

    public static void registerNativeExecutionTable(
            String schemaName,
            String tableName,
            List<String> columnNames,
            List<String> typeNames) throws SQLException {
        registerNativeExecutionTable(null, schemaName, tableName, columnNames, typeNames);
    }

    /**
     * Opens provider-owned table access for native ResultSet execution.
     *
     * <p>If the in-memory registry was cleared while Derby catalog metadata
     * survived, this method reconstructs the native table entry from the
     * supplied {@link TableDescriptor} and opens an existing provider table when
     * one exists.  That closes the TABLES-vs-catalog split exposed after G6.</p>
     */
    public static Optional<NativeExecutionTableAccess> openNativeExecutionTableAccess(
            TableDescriptor tableDescriptor) throws SQLException {
        return openNativeExecutionTableAccess(null, tableDescriptor);
    }

    /**
     * Opens provider-owned table access under the Derby transaction owner when
     * one is supplied. MODULE5C moves native MVCC execution from statement-local
     * auto-commit contexts toward Derby transaction lifecycle ownership.
     */
    public static Optional<NativeExecutionTableAccess> openNativeExecutionTableAccess(
            Object derbyTransactionOwner,
            TableDescriptor tableDescriptor) throws SQLException {
        Objects.requireNonNull(tableDescriptor, "tableDescriptor");
        if (!isDelosMvcc(tableDescriptor.getStorageProviderName())) {
            return Optional.empty();
        }

        TableDefinition table = tableDefinition(derbyTransactionOwner, tableDescriptor);
        StatementTransaction statementTx = beginStatementTransaction(derbyTransactionOwner, table);
        return Optional.of(new NativeExecutionTableAccess(
                table,
                table.tableAccess(),
                delosAccessContext(statementTx),
                statementTx));
    }

    /** Completes any MVCC work attached to a Derby transaction owner. */
    public static void commitDerbyTransaction(Object derbyTransactionOwner) throws SQLException {
        completeDerbyTransaction(derbyTransactionOwner, true);
    }

    /** Aborts any MVCC work attached to a Derby transaction owner. */
    public static void rollbackDerbyTransaction(Object derbyTransactionOwner) throws SQLException {
        completeDerbyTransaction(derbyTransactionOwner, false);
    }

    /** Test-only hook used by G-post restart/reopen smokes. */
    public static void clearRegisteredTablesForTesting() {
        synchronized (LOCK) {
            TABLES.clear();
            DERBY_TRANSACTION_SCOPES.clear();
            CACHED_PROVIDERS.clear();
        }
    }

    /** Test-only proof that native execution does not use the retired SQL bridge registry. */
    public static boolean hasRegisteredTableForTesting(String schemaName, String tableName) {
        VersionedTableMetadata metadata = metadata(schemaName, tableName);
        synchronized (LOCK) {
            return TABLES.keySet().stream().anyMatch(key -> key.metadata().equals(metadata));
        }
    }

    public static final class NativeExecutionTableAccess implements AutoCloseable {
        private final TableDefinition table;
        private final EngineMvccTableAccess tableAccess;
        private final DelosAccessContext context;
        private final StatementTransaction statementTx;
        private boolean closed;

        private NativeExecutionTableAccess(
                TableDefinition table,
                EngineMvccTableAccess tableAccess,
                DelosAccessContext context,
                StatementTransaction statementTx) {
            this.table = Objects.requireNonNull(table, "table");
            this.tableAccess = Objects.requireNonNull(tableAccess, "tableAccess");
            this.context = Objects.requireNonNull(context, "context");
            this.statementTx = Objects.requireNonNull(statementTx, "statementTx");
        }

        public EngineMvccTableAccess tableAccess() {
            return tableAccess;
        }

        public DelosAccessContext context() {
            return context;
        }

        public long insert(List<Object> nativeValues) throws SQLException {
            Objects.requireNonNull(nativeValues, "nativeValues");
            if (nativeValues.size() != table.columns().size()) {
                throw sqlException("42802", "INSERT value count does not match delos_mvcc table column count");
            }
            long rowKey = table.nextRowKey();
            table.reserveUniqueKeys(nativeValues, rowKey, statementTx.context());
            List<StoreDataValue> values = new ArrayList<>(nativeValues.size());
            for (Object value : nativeValues) {
                values.add(EngineMvccTableAccess.value(value));
            }
            tableAccess.insert(
                    context,
                    DelosRow.withIdentity(new NativeExecutionRowIdentity(rowKey), values));
            return 1L;
        }

        public long delete(DelosRowIdentity rowIdentity) throws SQLException {
            Objects.requireNonNull(rowIdentity, "rowIdentity");
            DelosMutationResult result = tableAccess.delete(context, rowIdentity);
            return result.affectedRows();
        }

        public long update(DelosRowIdentity rowIdentity, List<Object> nativeValues) throws SQLException {
            Objects.requireNonNull(rowIdentity, "rowIdentity");
            Objects.requireNonNull(nativeValues, "nativeValues");
            if (nativeValues.size() != table.columns().size()) {
                throw sqlException("42802", "UPDATE replacement value count does not match delos_mvcc table column count");
            }
            List<StoreDataValue> values = new ArrayList<>(nativeValues.size());
            for (Object value : nativeValues) {
                values.add(EngineMvccTableAccess.value(value));
            }
            DelosMutationResult result = tableAccess.update(
                    context,
                    rowIdentity,
                    DelosRow.withoutIdentity(List.copyOf(values)));
            return result.affectedRows();
        }

        @Override
        public void close() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            if (statementTx.derbyTransactionScoped()) {
                return;
            }
            finishStandaloneStatementTransaction(statementTx, table);
        }

        public void abort() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            if (statementTx.derbyTransactionScoped()) {
                statementTx.scope().markRollbackOnly();
                return;
            }
            abortStandaloneStatementTransaction(statementTx, table);
        }
    }

    private static TableDefinition tableDefinition(
            Object derbyTransactionOwner,
            TableDescriptor tableDescriptor) throws SQLException {
        String scopeKey = databaseScope(derbyTransactionOwner);
        VersionedTableMetadata metadata = metadata(tableDescriptor.getSchemaName(), tableDescriptor.getName());
        NativeTableKey tableKey = new NativeTableKey(scopeKey, metadata);
        synchronized (LOCK) {
            TableDefinition table = TABLES.get(tableKey);
            if (table != null) {
                return table;
            }

            VersionedStorageProvider provider = provider(scopeKey);
            VersionedTable<Long, List<Object>> providerTable;
            try {
                providerTable = provider.openTable(metadata);
            } catch (RuntimeException missingProviderTable) {
                providerTable = TABLE_OPERATION_BRIDGE.createTable(provider, metadata);
            }
            TableDefinition registered = new TableDefinition(
                    metadata,
                    columnsFrom(tableDescriptor),
                    providerTable,
                    provider.transactionCoordinator());
            TABLES.put(tableKey, registered);
            return registered;
        }
    }

    private static List<ColumnDefinition> columnsFrom(TableDescriptor tableDescriptor) throws SQLException {
        List<ColumnDefinition> columns = new ArrayList<>(tableDescriptor.getColumnDescriptorList().size());
        for (ColumnDescriptor columnDescriptor : tableDescriptor.getColumnDescriptorList()) {
            columns.add(ColumnDefinition.parse(
                    columnDescriptor.getColumnName(),
                    nativeDelosTypeName(columnDescriptor.getType())));
        }
        if (columns.isEmpty()) {
            throw sqlException("42X14", "CREATE TABLE USING delos_mvcc requires at least one column");
        }
        return List.copyOf(columns);
    }

    private static String nativeDelosTypeName(DataTypeDescriptor dataType) throws SQLException {
        int jdbcType = dataType.getTypeId().getJDBCTypeId();
        if (jdbcType == Types.INTEGER) {
            return "INTEGER";
        }
        if (jdbcType == Types.VARCHAR) {
            return "VARCHAR(" + dataType.getMaximumWidth() + ")";
        }
        if (jdbcType == Types.CHAR) {
            return "CHAR(" + dataType.getMaximumWidth() + ")";
        }
        throw sqlException("0A000", "Unsupported delos_mvcc column type: " + dataType.getFullSQLTypeName());
    }

    private static VersionedTableMetadata metadata(String schemaName, String tableName) {
        return new VersionedTableMetadata(
                schemaName.trim().toUpperCase(Locale.ROOT),
                tableName.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean isDelosMvcc(String storageProviderName) {
        return storageProviderName != null
                && PROVIDER_NAME.equals(storageProviderName.trim().toLowerCase(Locale.ROOT));
    }

    private static VersionedStorageProvider provider(String scopeKey) throws SQLException {
        synchronized (LOCK) {
            VersionedStorageProvider provider = CACHED_PROVIDERS.get(scopeKey);
            if (provider != null) {
                return provider;
            }
            VersionedStorageProvider discovered = discoverProvider();
            VersionedStorageProvider scoped = DEFAULT_DATABASE_SCOPE.equals(scopeKey)
                    ? discovered
                    : discovered.openForDatabase(Path.of(scopeKey));
            CACHED_PROVIDERS.put(scopeKey, scoped);
            return scoped;
        }
    }

    private static VersionedStorageProvider discoverProvider() throws SQLException {
        return VersionedStorageProviderDiscovery.discover()
                .stream()
                .filter(candidate -> PROVIDER_NAME.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> sqlException("0A000", "VersionedStorageProvider not discovered: " + PROVIDER_NAME));
    }

    private static String databaseScope(Object derbyTransactionOwner) {
        if (derbyTransactionOwner instanceof LanguageConnectionContext lcc) {
            String dbName = lcc.getDbname();
            if (dbName != null && !dbName.isBlank()) {
                return dbName;
            }
        }
        return DEFAULT_DATABASE_SCOPE;
    }

    private static StatementTransaction beginStatementTransaction(Object derbyTransactionOwner, TableDefinition table) {
        if (derbyTransactionOwner == null) {
            TxContext context = table.coordinator().begin();
            return StatementTransaction.standalone(table.coordinator(), context);
        }

        synchronized (LOCK) {
            TransactionScope scope = DERBY_TRANSACTION_SCOPES.computeIfAbsent(
                    derbyTransactionOwner, ignored -> new TransactionScope());
            return scope.transactionFor(table);
        }
    }

    private static void finishStandaloneStatementTransaction(
            StatementTransaction statementTx,
            TableDefinition table) throws SQLException {
        try {
            statementTx.coordinator().commit(statementTx.context());
            completeTableReservations(table, statementTx, true);
        } catch (RuntimeException e) {
            completeTableReservations(table, statementTx, false);
            throw sqlException("X0MV1", "Could not commit delos_mvcc statement transaction: " + e.getMessage(), e);
        }
    }

    private static void abortStandaloneStatementTransaction(
            StatementTransaction statementTx,
            TableDefinition table) throws SQLException {
        try {
            statementTx.coordinator().abort(statementTx.context());
            completeTableReservations(table, statementTx, false);
        } catch (RuntimeException e) {
            completeTableReservations(table, statementTx, false);
            throw sqlException("X0MV1", "Could not abort delos_mvcc statement transaction: " + e.getMessage(), e);
        }
    }

    private static void completeDerbyTransaction(Object derbyTransactionOwner, boolean commit) throws SQLException {
        if (derbyTransactionOwner == null) {
            return;
        }

        TransactionScope scope;
        synchronized (LOCK) {
            scope = DERBY_TRANSACTION_SCOPES.remove(derbyTransactionOwner);
        }
        if (scope == null) {
            return;
        }
        scope.complete(commit);
    }

    private static void completeTableReservations(
            TableDefinition table,
            StatementTransaction statementTx,
            boolean committed) {
        table.completeUniqueReservations(statementTx.context().transactionId(), committed);
        table.tableAccess().completeMutationReservations(delosAccessContext(statementTx), committed);
    }

    private static DelosAccessContext delosAccessContext(StatementTransaction statementTx) {
        return DelosAccessContext.builder(true)
                .put(EngineMvccTableAccess.TX_CONTEXT_KEY, statementTx.context())
                .put(EngineMvccTableAccess.TX_VIEW_KEY, statementTx.context().currentView())
                .build();
    }

    private static SQLException sqlException(String sqlState, String message) {
        return new SQLException("(" + sqlState + ") " + message, sqlState);
    }

    private static SQLException sqlException(String sqlState, String message, Throwable cause) {
        return new SQLException("(" + sqlState + ") " + message, sqlState, cause);
    }

    private record NativeExecutionRowIdentity(Object nativeIdentity) implements DelosRowIdentity {
        @Override
        public String providerName() {
            return PROVIDER_NAME;
        }
    }

    private static final class TransactionScope {
        private final Map<VersionedTransactionCoordinator, StatementTransaction> transactionsByCoordinator = new IdentityHashMap<>();
        private final Set<TableDefinition> touchedTables = new HashSet<>();
        private boolean rollbackOnly;

        private StatementTransaction transactionFor(TableDefinition table) {
            touchedTables.add(table);
            return transactionsByCoordinator.computeIfAbsent(table.coordinator(), coordinator ->
                    StatementTransaction.scoped(this, coordinator, coordinator.begin()));
        }

        private void markRollbackOnly() {
            rollbackOnly = true;
        }

        private void complete(boolean commit) throws SQLException {
            boolean commitScope = commit && !rollbackOnly;
            SQLException failure = null;

            for (StatementTransaction transaction : transactionsByCoordinator.values()) {
                try {
                    if (commitScope) {
                        transaction.coordinator().commit(transaction.context());
                    } else {
                        transaction.coordinator().abort(transaction.context());
                    }
                } catch (RuntimeException e) {
                    SQLException wrapped = sqlException("X0MV1",
                            "Could not " + (commitScope ? "commit" : "abort")
                                    + " delos_mvcc Derby transaction: " + e.getMessage(), e);
                    if (failure == null) {
                        failure = wrapped;
                    } else {
                        failure.addSuppressed(wrapped);
                    }
                }
            }

            for (TableDefinition table : touchedTables) {
                StatementTransaction transaction = transactionsByCoordinator.get(table.coordinator());
                if (transaction != null) {
                    completeTableReservations(table, transaction, commitScope && failure == null);
                }
            }

            if (failure != null) {
                throw failure;
            }
        }
    }

    private record StatementTransaction(
            VersionedTransactionCoordinator coordinator,
            TxContext context,
            TransactionScope scope) {
        private static StatementTransaction standalone(
                VersionedTransactionCoordinator coordinator,
                TxContext context) {
            return new StatementTransaction(coordinator, context, null);
        }

        private static StatementTransaction scoped(
                TransactionScope scope,
                VersionedTransactionCoordinator coordinator,
                TxContext context) {
            return new StatementTransaction(coordinator, context, scope);
        }

        private boolean derbyTransactionScoped() {
            return scope != null;
        }
    }

    private static final class TableDefinition {
        private final VersionedTableMetadata metadata;
        private final List<ColumnDefinition> columns;
        private final List<UniqueConstraint> uniqueConstraints;
        private final Map<UniqueKey, UniqueReservation> uniqueReservations = new HashMap<>();
        private final Map<String, IndexDefinition> indexesByName = new HashMap<>();
        private final VersionedTable<Long, List<Object>> table;
        private final VersionedTransactionCoordinator coordinator;
        private long nextKey = 1L;

        private TableDefinition(
                VersionedTableMetadata metadata,
                List<ColumnDefinition> columns,
                VersionedTable<Long, List<Object>> table,
                VersionedTransactionCoordinator coordinator) throws SQLException {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            this.columns = List.copyOf(columns);
            this.uniqueConstraints = uniqueConstraintsFrom(columns);
            this.table = Objects.requireNonNull(table, "table");
            this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        }

        private List<ColumnDefinition> columns() {
            return columns;
        }

        private VersionedTransactionCoordinator coordinator() {
            return coordinator;
        }

        private EngineMvccTableAccess tableAccess() {
            List<EngineMvccTableAccess.IndexBinding> indexBindings = new ArrayList<>();
            for (IndexDefinition definition : indexesByName.values()) {
                indexBindings.add(new EngineMvccTableAccess.IndexBinding(
                        definition.metadata().indexName(),
                        columns.get(definition.columnIndex()).name(),
                        isUniqueLookupColumn(definition.columnIndex()),
                        definition.index()));
            }
            return new EngineMvccTableAccess(
                    DelosTableIdentity.of(metadata.schemaName(), metadata.tableName()),
                    tableShape(),
                    table,
                    TABLE_OPERATION_BRIDGE,
                    indexBindings);
        }

        private DelosTableShape tableShape() {
            List<DelosTableShape.Column> shapeColumns = new ArrayList<>();
            for (ColumnDefinition column : columns) {
                shapeColumns.add(new DelosTableShape.Column(column.name(), column.typeName(), true));
            }
            return DelosTableShape.of(shapeColumns);
        }

        private boolean isUniqueLookupColumn(int columnIndex) {
            ColumnDefinition column = columns.get(columnIndex);
            return column.primaryKey() || column.unique();
        }

        private synchronized long nextRowKey() {
            return nextKey++;
        }

        private synchronized void reserveUniqueKeys(List<Object> values, long rowKey, TxContext transaction) throws SQLException {
            if (uniqueConstraints.isEmpty()) {
                return;
            }
            long transactionId = transaction.transactionId();
            List<UniqueKey> reserved = new ArrayList<>();
            try {
                for (UniqueConstraint constraint : uniqueConstraints) {
                    Object value = values.get(constraint.columnIndex());
                    if (value == null) {
                        if (constraint.primaryKey()) {
                            throw sqlException("23502", "Primary key column " + columns.get(constraint.columnIndex()).name()
                                    + " cannot be NULL for delos_mvcc table " + metadata.qualifiedName());
                        }
                        continue;
                    }

                    UniqueKey key = new UniqueKey(constraint.name(), value);
                    UniqueReservation existing = uniqueReservations.get(key);
                    if (existing != null) {
                        throw duplicateKeyException(constraint, value, existing);
                    }
                    uniqueReservations.put(key, new UniqueReservation(transactionId, rowKey, UniqueReservationStatus.ACTIVE));
                    reserved.add(key);
                }
            } catch (SQLException | RuntimeException e) {
                for (UniqueKey key : reserved) {
                    UniqueReservation reservation = uniqueReservations.get(key);
                    if (reservation != null && reservation.transactionId() == transactionId && reservation.rowKey() == rowKey
                            && reservation.status() == UniqueReservationStatus.ACTIVE) {
                        uniqueReservations.remove(key);
                    }
                }
                throw e;
            }
        }

        private synchronized void completeUniqueReservations(long transactionId, boolean commit) {
            if (uniqueReservations.isEmpty()) {
                return;
            }
            if (commit) {
                for (Map.Entry<UniqueKey, UniqueReservation> entry : new ArrayList<>(uniqueReservations.entrySet())) {
                    UniqueReservation reservation = entry.getValue();
                    if (reservation.transactionId() == transactionId && reservation.status() == UniqueReservationStatus.ACTIVE) {
                        entry.setValue(new UniqueReservation(transactionId, reservation.rowKey(), UniqueReservationStatus.COMMITTED));
                    }
                }
                return;
            }
            uniqueReservations.entrySet().removeIf(entry -> {
                UniqueReservation reservation = entry.getValue();
                return reservation.transactionId() == transactionId && reservation.status() == UniqueReservationStatus.ACTIVE;
            });
        }

        private static SQLException duplicateKeyException(
                UniqueConstraint constraint,
                Object value,
                UniqueReservation existing) {
            String state = existing.status() == UniqueReservationStatus.ACTIVE ? "40XL1" : "23505";
            String visibility = existing.status() == UniqueReservationStatus.ACTIVE
                    ? "reserved by an active delos_mvcc transaction"
                    : "already committed";
            return sqlException(state, "Duplicate " + constraint.kind() + " value " + value
                    + " for delos_mvcc constraint " + constraint.name() + " (" + visibility + ")");
        }

        private static List<UniqueConstraint> uniqueConstraintsFrom(List<ColumnDefinition> columns) throws SQLException {
            List<UniqueConstraint> constraints = new ArrayList<>();
            boolean sawPrimaryKey = false;
            for (int i = 0; i < columns.size(); i++) {
                ColumnDefinition column = columns.get(i);
                if (column.primaryKey()) {
                    if (sawPrimaryKey) {
                        throw sqlException("42X14", "delos_mvcc supports only one PRIMARY KEY column");
                    }
                    sawPrimaryKey = true;
                    constraints.add(new UniqueConstraint("PK_" + column.name(), i, true));
                } else if (column.unique()) {
                    constraints.add(new UniqueConstraint("UQ_" + column.name(), i, false));
                }
            }
            return List.copyOf(constraints);
        }
    }

    private record NativeTableKey(String databaseScope, VersionedTableMetadata metadata) {
        private NativeTableKey {
            databaseScope = Objects.requireNonNull(databaseScope, "databaseScope");
            metadata = Objects.requireNonNull(metadata, "metadata");
        }
    }

    private record IndexDefinition(
            VersionedIndexMetadata metadata,
            int columnIndex,
            VersionedIndex<Long, List<Object>> index) {
    }

    private record UniqueConstraint(String name, int columnIndex, boolean primaryKey) {
        private String kind() {
            return primaryKey ? "primary-key" : "unique";
        }
    }

    private record UniqueKey(String constraintName, Object value) {
    }

    private enum UniqueReservationStatus {
        ACTIVE,
        COMMITTED
    }

    private record UniqueReservation(long transactionId, long rowKey, UniqueReservationStatus status) {
    }

    private record ColumnDefinition(String name, int jdbcType, String typeName, boolean primaryKey, boolean unique) {
        static ColumnDefinition parse(String name, String typeText) throws SQLException {
            String trimmedType = typeText.trim();
            String normalizedType = trimmedType.toUpperCase(Locale.ROOT);
            boolean primaryKey = false;
            boolean unique = false;

            if (normalizedType.endsWith(" PRIMARY KEY")) {
                primaryKey = true;
                unique = true;
                normalizedType = normalizedType.substring(0, normalizedType.length() - " PRIMARY KEY".length()).trim();
            } else if (normalizedType.endsWith(" UNIQUE")) {
                unique = true;
                normalizedType = normalizedType.substring(0, normalizedType.length() - " UNIQUE".length()).trim();
            }

            String normalizedName = name.toUpperCase(Locale.ROOT);
            if (normalizedType.equals("INT") || normalizedType.equals("INTEGER")) {
                return new ColumnDefinition(normalizedName, Types.INTEGER, "INTEGER", primaryKey, unique);
            }
            if (normalizedType.matches("VARCHAR\\s*\\(\\s*[0-9]+\\s*\\)")) {
                return new ColumnDefinition(normalizedName, Types.VARCHAR, normalizedType.replaceAll("\\s+", ""), primaryKey, unique);
            }
            if (normalizedType.matches("CHAR\\s*\\(\\s*[0-9]+\\s*\\)")) {
                return new ColumnDefinition(normalizedName, Types.CHAR, normalizedType.replaceAll("\\s+", ""), primaryKey, unique);
            }
            throw sqlException("0A000", "Unsupported delos_mvcc column type: " + typeText.trim());
        }
    }
}
