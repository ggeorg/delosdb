package io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.VersionedStorageProviderDiscovery;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedWriteConflictException;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal SQL/JDBC bridge for the experimental {@code delos_mvcc} provider.
 *
 * <p>This is deliberately tiny and quarantined. It exists to prove the first
 * user-visible Phase 4 behavior while the normal Derby-compatible compiler,
 * heap store, indexes, WAL, and optimizer remain untouched. Supported SQL is
 * intentionally narrow:</p>
 *
 * <pre>
 * CREATE TABLE name (id INT, value VARCHAR(40)) USING delos_mvcc
 * CREATE TABLE name (id INT, value VARCHAR(40))
 *     -- only when -Ddelosdb.storage.defaultProvider=delos_mvcc is set
 * INSERT INTO name VALUES (1, 'alpha')
 * SELECT * FROM name
 * SELECT * FROM name ORDER BY indexed_column [ASC|DESC]
 * SELECT COUNT(*) FROM name
 * CREATE INDEX idx_name ON name(column_name)
 * SELECT * FROM name WHERE column_name = 'literal'
 * SELECT * FROM name WHERE column_name >= 'literal'
 * SELECT * FROM name WHERE column_name BETWEEN 'a' AND 'z'
 * UPDATE name SET column_name = 'new' WHERE indexed_column = 'old'
 * DELETE FROM name WHERE indexed_column = 'old'
 * </pre>
 *
 * <p>Auto-commit statements commit through the provider-local transaction coordinator.
 * When Derby auto-commit is disabled, the bridge now keeps one provider-local
 * MVCC transaction per JDBC connection owner and completes it from
 * {@link #commit(Object)} or {@link #rollback(Object)}.</p>
 */
@InternalApi
public final class VersionedStorageSqlBridge {
    private static final String PROVIDER_NAME = "delos_mvcc";
    private static final String DEFAULT_STORAGE_PROVIDER_PROPERTY = "delosdb.storage.defaultProvider";
    private static final String DEFAULT_SCHEMA = "APP";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^create\\s+table\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s*\\((.*)\\)(?:\\s+using\\s+([a-zA-Z_][a-zA-Z0-9_]*))?$");
    private static final Pattern INSERT_VALUES = Pattern.compile(
            "(?is)^insert\\s+into\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s+values\\s*\\((.*)\\)$");
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)^create\\s+index\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+on\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\)$");
    private static final Pattern SELECT_ALL = Pattern.compile(
            "(?is)^select\\s+\\*\\s+from\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)(?:\\s+order\\s+by\\s+([a-zA-Z_][a-zA-Z0-9_]*)(?:\\s+(asc|desc))?)?$");
    private static final Pattern SELECT_COUNT = Pattern.compile(
            "(?is)^select\\s+count\\s*\\(\\s*\\*\\s*\\)\\s+from\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)$");
    private static final Pattern SELECT_WHERE_EQUALS = Pattern.compile(
            "(?is)^select\\s+\\*\\s+from\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s+where\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+?)$");
    private static final Pattern SELECT_WHERE_RANGE = Pattern.compile(
            "(?is)^select\\s+\\*\\s+from\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s+where\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*(>=|<=|>|<)\\s*(.+?)$");
    private static final Pattern SELECT_WHERE_BETWEEN = Pattern.compile(
            "(?is)^select\\s+\\*\\s+from\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s+where\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+between\\s+(.+?)\\s+and\\s+(.+?)$");
    private static final Pattern UPDATE_WHERE_EQUALS = Pattern.compile(
            "(?is)^update\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s+set\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+?)\\s+where\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+?)$");
    private static final Pattern DELETE_WHERE_EQUALS = Pattern.compile(
            "(?is)^delete\\s+from\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s+where\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+?)$");

    private static final Object LOCK = new Object();
    private static final Map<VersionedTableMetadata, TableDefinition> TABLES = new HashMap<>();
    private static final Map<Object, SessionTransaction> SESSION_TRANSACTIONS = new IdentityHashMap<>();
    private static VersionedStorageProvider cachedProvider;
    private static Path pageBackedStorageDirectory;
    private static volatile VersionedStorageAccessPath lastAccessPath;

    private VersionedStorageSqlBridge() {
    }

    /**
     * Attempts to execute a supported experimental MVCC SQL statement.
     *
     * @return a result when this bridge handled the SQL, or {@code null} when
     *         normal Derby execution should continue.
     */
    public static VersionedStorageSqlResult tryExecute(String sql) throws SQLException {
        return tryExecute(sql, VersionedStorageSqlBridge.class, true);
    }

    /**
     * Returns the last provider-owned MVCC access path selected by this bridge.
     * This is a Phase 13 diagnostic hook for smoke tests and future runtime
     * statistics; it is not a stable public SQL API.
     */
    public static Optional<VersionedStorageAccessPath> lastAccessPath() {
        return Optional.ofNullable(lastAccessPath);
    }

    /**
     * Configures the experimental SQL bridge to use the page-backed delos_mvcc
     * provider storage. This is intentionally internal and exists for the
     * Phase A durable SQL proof; the normal Derby heap path is untouched.
     */
    public static void configurePageBackedStorage(Path storageDirectory) throws SQLException {
        Objects.requireNonNull(storageDirectory, "storageDirectory");
        synchronized (LOCK) {
            pageBackedStorageDirectory = storageDirectory;
            cachedProvider = openPageBackedProvider(storageDirectory);
            SESSION_TRANSACTIONS.clear();
            reopenTablesWithProvider(cachedProvider);
        }
    }

    /** Reopens the configured page-backed provider while keeping bridge table metadata. */
    public static void reopenPageBackedStorage() throws SQLException {
        synchronized (LOCK) {
            if (pageBackedStorageDirectory == null) {
                throw sqlException("X0MV4", "delos_mvcc page-backed storage has not been configured");
            }
            cachedProvider = openPageBackedProvider(pageBackedStorageDirectory);
            SESSION_TRANSACTIONS.clear();
            reopenTablesWithProvider(cachedProvider);
        }
    }

    /**
     * Attempts to execute a supported experimental MVCC SQL statement using
     * the supplied transaction owner. The owner is normally the current
     * EmbedConnection; it lets explicit commit/rollback complete the provider
     * transaction without leaking MVCC state into Derby heap storage.
     */
    public static VersionedStorageSqlResult tryExecute(
            String sql,
            Object transactionOwner,
            boolean autoCommit) throws SQLException {
        return tryExecute(sql, transactionOwner, autoCommit, Connection.TRANSACTION_READ_COMMITTED);
    }

    /**
     * Attempts to execute a supported experimental MVCC SQL statement using the
     * current JDBC isolation level.
     *
     * <p>READ COMMITTED and READ UNCOMMITTED capture a fresh provider snapshot
     * per statement. REPEATABLE READ and SERIALIZABLE keep the provider
     * transaction snapshot stable until JDBC commit/rollback.</p>
     */
    public static VersionedStorageSqlResult tryExecute(
            String sql,
            Object transactionOwner,
            boolean autoCommit,
            int transactionIsolation) throws SQLException {
        try {
            String normalizedSql = stripTerminator(sql);

            Matcher create = CREATE_TABLE.matcher(normalizedSql);
        if (create.matches()) {
            if (shouldHandleCreateTable(create.group(3))) {
                return createTable(create.group(1), create.group(2));
            }
            return null;
        }

        Matcher insert = INSERT_VALUES.matcher(normalizedSql);
        if (insert.matches()) {
            Optional<TableDefinition> table = findTable(insert.group(1));
            if (table.isPresent()) {
                return insertValues(table.get(), insert.group(2), transactionOwner, autoCommit, transactionIsolation);
            }
            return null;
        }

        Matcher createIndex = CREATE_INDEX.matcher(normalizedSql);
        if (createIndex.matches()) {
            Optional<TableDefinition> table = findTable(createIndex.group(2));
            if (table.isPresent()) {
                return createIndex(table.get(), createIndex.group(1), createIndex.group(3));
            }
            return null;
        }

        Matcher updateWhere = UPDATE_WHERE_EQUALS.matcher(normalizedSql);
        if (updateWhere.matches()) {
            Optional<TableDefinition> table = findTable(updateWhere.group(1));
            if (table.isPresent()) {
                return updateWhereEquals(
                        table.get(),
                        updateWhere.group(2),
                        updateWhere.group(3),
                        updateWhere.group(4),
                        updateWhere.group(5),
                        transactionOwner,
                        autoCommit,
                        transactionIsolation);
            }
            return null;
        }

        Matcher deleteWhere = DELETE_WHERE_EQUALS.matcher(normalizedSql);
        if (deleteWhere.matches()) {
            Optional<TableDefinition> table = findTable(deleteWhere.group(1));
            if (table.isPresent()) {
                return deleteWhereEquals(table.get(), deleteWhere.group(2), deleteWhere.group(3), transactionOwner, autoCommit, transactionIsolation);
            }
            return null;
        }

        Matcher selectBetween = SELECT_WHERE_BETWEEN.matcher(normalizedSql);
        if (selectBetween.matches()) {
            Optional<TableDefinition> table = findTable(selectBetween.group(1));
            if (table.isPresent()) {
                return selectWhereRange(
                        table.get(),
                        selectBetween.group(2),
                        "between",
                        selectBetween.group(3),
                        selectBetween.group(4),
                        transactionOwner,
                        autoCommit,
                        transactionIsolation);
            }
            return null;
        }

        Matcher selectRange = SELECT_WHERE_RANGE.matcher(normalizedSql);
        if (selectRange.matches()) {
            Optional<TableDefinition> table = findTable(selectRange.group(1));
            if (table.isPresent()) {
                return selectWhereRange(
                        table.get(),
                        selectRange.group(2),
                        selectRange.group(3),
                        selectRange.group(4),
                        null,
                        transactionOwner,
                        autoCommit,
                        transactionIsolation);
            }
            return null;
        }

        Matcher selectWhere = SELECT_WHERE_EQUALS.matcher(normalizedSql);
        if (selectWhere.matches()) {
            Optional<TableDefinition> table = findTable(selectWhere.group(1));
            if (table.isPresent()) {
                return selectWhereEquals(table.get(), selectWhere.group(2), selectWhere.group(3), transactionOwner, autoCommit, transactionIsolation);
            }
            return null;
        }

        Matcher selectAll = SELECT_ALL.matcher(normalizedSql);
        if (selectAll.matches()) {
            Optional<TableDefinition> table = findTable(selectAll.group(1));
            if (table.isPresent()) {
                return selectAll(table.get(), selectAll.group(2), selectAll.group(3), transactionOwner, autoCommit, transactionIsolation);
            }
            return null;
        }

        Matcher selectCount = SELECT_COUNT.matcher(normalizedSql);
        if (selectCount.matches()) {
            Optional<TableDefinition> table = findTable(selectCount.group(1));
            if (table.isPresent()) {
                return selectCount(table.get(), transactionOwner, autoCommit, transactionIsolation);
            }
            return null;
        }

            return null;
        } catch (VersionedWriteConflictException e) {
            throw sqlException("40XL1", "delos_mvcc write conflict: " + e.getMessage());
        }
    }

    private static boolean shouldHandleCreateTable(String explicitProviderName) throws SQLException {
        if (explicitProviderName != null) {
            return PROVIDER_NAME.equalsIgnoreCase(explicitProviderName.trim());
        }

        String defaultProvider = System.getProperty(DEFAULT_STORAGE_PROVIDER_PROPERTY);
        if (defaultProvider == null || defaultProvider.isBlank()) {
            return false;
        }
        if (PROVIDER_NAME.equalsIgnoreCase(defaultProvider.trim())) {
            return true;
        }
        throw sqlException("X0MV5", "Unsupported " + DEFAULT_STORAGE_PROVIDER_PROPERTY
                + " value for CREATE TABLE default-provider candidate path: " + defaultProvider);
    }

    private static VersionedStorageSqlResult createTable(String tableName, String columnList) throws SQLException {
        TableIdentity identity = TableIdentity.parse(tableName);
        VersionedTableMetadata metadata = new VersionedTableMetadata(identity.schemaName(), identity.tableName());
        List<ColumnDefinition> columns = parseColumns(columnList);
        if (columns.isEmpty()) {
            throw sqlException("42X14", "CREATE TABLE USING delos_mvcc requires at least one column");
        }

        synchronized (LOCK) {
            if (TABLES.containsKey(metadata)) {
                throw sqlException("X0Y32", "Versioned storage table already exists: " + metadata.qualifiedName());
            }

            VersionedStorageProvider provider = provider();
            VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
            TABLES.put(metadata, new TableDefinition(metadata, columns, table, provider.transactionCoordinator()));
        }
        return VersionedStorageSqlResult.updateCount(0L);
    }

    private static VersionedStorageSqlResult insertValues(
            TableDefinition table,
            String valueList,
            Object transactionOwner,
            boolean autoCommit,
            int transactionIsolation) throws SQLException {
        List<Object> values = parseValues(valueList, table.columns());
        if (values.size() != table.columns().size()) {
            throw sqlException("42802", "INSERT value count does not match delos_mvcc table column count");
        }

        StatementTransaction statementTx = beginStatementTransaction(table, transactionOwner, autoCommit, transactionIsolation);
        long rowKey = table.nextRowKey();
        try {
            table.reserveUniqueKeys(values, rowKey, statementTx.context());
            table.table().insert(rowKey, values, statementTx.context());
            finishStatementTransaction(statementTx);
            return VersionedStorageSqlResult.updateCount(1L);
        } catch (RuntimeException | SQLException e) {
            failStatementTransaction(transactionOwner, statementTx);
            throw e;
        }
    }

    private static VersionedStorageSqlResult createIndex(
            TableDefinition table,
            String indexName,
            String columnName) throws SQLException {
        int columnIndex = table.columnIndex(columnName);
        StatementTransaction statementTx = beginStatementTransaction(table, VersionedStorageSqlBridge.class, true, Connection.TRANSACTION_READ_COMMITTED);
        try {
            VersionedIndexMetadata indexMetadata = new VersionedIndexMetadata(
                    table.metadata(),
                    indexName,
                    table.columns().get(columnIndex).name(),
                    false);
            table.createIndex(indexMetadata, columnIndex, statementTx.context());
            finishStatementTransaction(statementTx);
            return VersionedStorageSqlResult.updateCount(0L);
        } catch (RuntimeException | SQLException e) {
            failStatementTransaction(VersionedStorageSqlBridge.class, statementTx);
            throw e;
        }
    }

    private static VersionedStorageSqlResult selectWhereRange(
            TableDefinition table,
            String columnName,
            String operator,
            String rawLeftValue,
            String rawRightValue,
            Object transactionOwner,
            boolean autoCommit,
            int transactionIsolation) throws SQLException {
        int columnIndex = table.columnIndex(columnName);
        PredicateRange range = PredicateRange.parse(table.columns().get(columnIndex), operator, rawLeftValue, rawRightValue);
        StatementTransaction statementTx = beginStatementTransaction(table, transactionOwner, autoCommit, transactionIsolation);
        try {
            VersionedTableStats tableStats = table.table().stats(statementTx.context().currentView());
            long tableScanCost = estimateTableScanCost(tableStats);
            Optional<IndexDefinition> indexDefinition = table.indexDefinitionForColumn(columnIndex);
            AccessPathSelection selection = chooseRangeAccessPath(
                    table,
                    columnIndex,
                    range,
                    statementTx,
                    tableStats,
                    tableScanCost,
                    indexDefinition);

            CachedRowSet rowSet = newRowSet(table.columns());
            List<List<Object>> rows = selection.rows();
            for (int i = rows.size() - 1; i >= 0; i--) {
                append(rowSet, rows.get(i), table.columns());
            }
            rowSet.beforeFirst();
            lastAccessPath = selection.accessPath();
            finishStatementTransaction(statementTx);
            return VersionedStorageSqlResult.rows(rowSet);
        } catch (RuntimeException | SQLException e) {
            failStatementTransaction(transactionOwner, statementTx);
            throw e;
        }
    }

    private static AccessPathSelection chooseRangeAccessPath(
            TableDefinition table,
            int columnIndex,
            PredicateRange range,
            StatementTransaction statementTx,
            VersionedTableStats tableStats,
            long tableScanCost,
            Optional<IndexDefinition> indexDefinition) throws SQLException {
        if (indexDefinition.isPresent()) {
            IndexDefinition index = indexDefinition.get();
            VersionedIndexStats indexStats = index.index().statsRange(
                    range.lowerBound(),
                    range.lowerInclusive(),
                    range.upperBound(),
                    range.upperInclusive(),
                    statementTx.context().currentView());
            long indexLookupCost = indexStats.estimatedLookupCost();
            if (indexLookupCost <= tableScanCost) {
                List<List<Object>> rows = valuesFrom(index.index().lookupRange(
                        range.lowerBound(),
                        range.lowerInclusive(),
                        range.upperBound(),
                        range.upperInclusive(),
                        statementTx.context().currentView()));
                VersionedStorageAccessPath accessPath = new VersionedStorageAccessPath(
                        table.metadata().qualifiedName(),
                        "select-range",
                        VersionedStorageAccessPath.INDEX_SCAN,
                        table.columns().get(columnIndex).name(),
                        index.metadata().indexName(),
                        tableStats.visibleRowCount(),
                        tableStats.physicalVersionCount(),
                        tableStats.deadVersionEstimate(),
                        indexStats.candidateCount(),
                        indexStats.visibleMatchCount(),
                        tableScanCost,
                        indexLookupCost);
                return new AccessPathSelection(rows, accessPath);
            }

            List<List<Object>> rows = scanWhereRange(table, columnIndex, range, statementTx);
            VersionedStorageAccessPath accessPath = new VersionedStorageAccessPath(
                    table.metadata().qualifiedName(),
                    "select-range",
                    VersionedStorageAccessPath.TABLE_SCAN,
                    table.columns().get(columnIndex).name(),
                    index.metadata().indexName(),
                    tableStats.visibleRowCount(),
                    tableStats.physicalVersionCount(),
                    tableStats.deadVersionEstimate(),
                    indexStats.candidateCount(),
                    indexStats.visibleMatchCount(),
                    tableScanCost,
                    indexLookupCost);
            return new AccessPathSelection(rows, accessPath);
        }

        List<List<Object>> rows = scanWhereRange(table, columnIndex, range, statementTx);
        VersionedStorageAccessPath accessPath = new VersionedStorageAccessPath(
                table.metadata().qualifiedName(),
                "select-range",
                VersionedStorageAccessPath.TABLE_SCAN,
                table.columns().get(columnIndex).name(),
                "",
                tableStats.visibleRowCount(),
                tableStats.physicalVersionCount(),
                tableStats.deadVersionEstimate(),
                0L,
                rows.size(),
                tableScanCost,
                0L);
        return new AccessPathSelection(rows, accessPath);
    }

    private static VersionedStorageSqlResult selectWhereEquals(
            TableDefinition table,
            String columnName,
            String rawValue,
            Object transactionOwner,
            boolean autoCommit,
            int transactionIsolation) throws SQLException {
        int columnIndex = table.columnIndex(columnName);
        Object predicateValue = table.columns().get(columnIndex).parseValue(rawValue.trim());
        StatementTransaction statementTx = beginStatementTransaction(table, transactionOwner, autoCommit, transactionIsolation);
        try {
            VersionedTableStats tableStats = table.table().stats(statementTx.context().currentView());
            long tableScanCost = estimateTableScanCost(tableStats);
            Optional<IndexDefinition> indexDefinition = table.indexDefinitionForColumn(columnIndex);
            AccessPathSelection selection = chooseAccessPath(
                    table,
                    columnIndex,
                    predicateValue,
                    statementTx,
                    tableStats,
                    tableScanCost,
                    indexDefinition);

            CachedRowSet rowSet = newRowSet(table.columns());
            List<List<Object>> rows = selection.rows();
            for (int i = rows.size() - 1; i >= 0; i--) {
                append(rowSet, rows.get(i), table.columns());
            }
            rowSet.beforeFirst();
            lastAccessPath = selection.accessPath();
            finishStatementTransaction(statementTx);
            return VersionedStorageSqlResult.rows(rowSet);
        } catch (RuntimeException | SQLException e) {
            failStatementTransaction(transactionOwner, statementTx);
            throw e;
        }
    }

    private static AccessPathSelection chooseAccessPath(
            TableDefinition table,
            int columnIndex,
            Object predicateValue,
            StatementTransaction statementTx,
            VersionedTableStats tableStats,
            long tableScanCost,
            Optional<IndexDefinition> indexDefinition) throws SQLException {
        if (indexDefinition.isPresent()) {
            IndexDefinition index = indexDefinition.get();
            VersionedIndexStats indexStats = index.index().stats(predicateValue, statementTx.context().currentView());
            long indexLookupCost = indexStats.estimatedLookupCost();
            if (table.isUniqueLookupColumn(columnIndex) || indexLookupCost <= tableScanCost) {
                List<List<Object>> rows = valuesFrom(index.index().lookup(predicateValue, statementTx.context().currentView()));
                VersionedStorageAccessPath accessPath = new VersionedStorageAccessPath(
                        table.metadata().qualifiedName(),
                        "select-where",
                        VersionedStorageAccessPath.INDEX_SCAN,
                        table.columns().get(columnIndex).name(),
                        index.metadata().indexName(),
                        tableStats.visibleRowCount(),
                        tableStats.physicalVersionCount(),
                        tableStats.deadVersionEstimate(),
                        indexStats.candidateCount(),
                        indexStats.visibleMatchCount(),
                        tableScanCost,
                        indexLookupCost);
                return new AccessPathSelection(rows, accessPath);
            }

            List<List<Object>> rows = scanWhere(table, columnIndex, predicateValue, statementTx);
            VersionedStorageAccessPath accessPath = new VersionedStorageAccessPath(
                    table.metadata().qualifiedName(),
                    "select-where",
                    VersionedStorageAccessPath.TABLE_SCAN,
                    table.columns().get(columnIndex).name(),
                    index.metadata().indexName(),
                    tableStats.visibleRowCount(),
                    tableStats.physicalVersionCount(),
                    tableStats.deadVersionEstimate(),
                    indexStats.candidateCount(),
                    indexStats.visibleMatchCount(),
                    tableScanCost,
                    indexLookupCost);
            return new AccessPathSelection(rows, accessPath);
        }

        List<List<Object>> rows = scanWhere(table, columnIndex, predicateValue, statementTx);
        VersionedStorageAccessPath accessPath = new VersionedStorageAccessPath(
                table.metadata().qualifiedName(),
                "select-where",
                VersionedStorageAccessPath.TABLE_SCAN,
                table.columns().get(columnIndex).name(),
                "",
                tableStats.visibleRowCount(),
                tableStats.physicalVersionCount(),
                tableStats.deadVersionEstimate(),
                0L,
                rows.size(),
                tableScanCost,
                0L);
        return new AccessPathSelection(rows, accessPath);
    }

    private static List<List<Object>> valuesFrom(VersionedScan<Long, List<Object>> scan) {
        List<List<Object>> rows = new ArrayList<>();
        try (scan) {
            while (scan.next()) {
                rows.add(scan.row().value());
            }
        }
        return rows;
    }

    private static List<List<Object>> scanWhere(
            TableDefinition table,
            int columnIndex,
            Object predicateValue,
            StatementTransaction statementTx) {
        List<List<Object>> rows = new ArrayList<>();
        try (VersionedScan<Long, List<Object>> scan = table.table().openScan(statementTx.context().currentView())) {
            while (scan.next()) {
                List<Object> row = scan.row().value();
                if (Objects.equals(predicateValue, row.get(columnIndex))) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static List<List<Object>> scanWhereRange(
            TableDefinition table,
            int columnIndex,
            PredicateRange range,
            StatementTransaction statementTx) {
        List<List<Object>> rows = new ArrayList<>();
        try (VersionedScan<Long, List<Object>> scan = table.table().openScan(statementTx.context().currentView())) {
            while (scan.next()) {
                List<Object> row = scan.row().value();
                if (range.matches(row.get(columnIndex))) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static int comparePredicateValues(Object left, Object right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Comparable<?> comparable && left.getClass().isInstance(right)) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            int result = ((Comparable) comparable).compareTo(right);
            return result;
        }
        int classCompare = left.getClass().getName().compareTo(right.getClass().getName());
        if (classCompare != 0) {
            return classCompare;
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static long estimateTableScanCost(VersionedTableStats tableStats) {
        return Math.max(1L, tableStats.visibleRowCount() + tableStats.deadVersionEstimate());
    }

    private static VersionedStorageSqlResult updateWhereEquals(
            TableDefinition table,
            String setColumnName,
            String rawSetValue,
            String whereColumnName,
            String rawWhereValue,
            Object transactionOwner,
            boolean autoCommit,
            int transactionIsolation) throws SQLException {
        int setColumnIndex = table.columnIndex(setColumnName);
        int whereColumnIndex = table.columnIndex(whereColumnName);
        Object newValue = table.columns().get(setColumnIndex).parseValue(rawSetValue.trim());
        Object whereValue = table.columns().get(whereColumnIndex).parseValue(rawWhereValue.trim());
        StatementTransaction statementTx = beginStatementTransaction(table, transactionOwner, autoCommit, transactionIsolation);
        try {
            List<VersionedRow<Long, List<Object>>> rows = table.indexedRows(whereColumnIndex, whereValue, statementTx.context());
            for (VersionedRow<Long, List<Object>> row : rows) {
                List<Object> replacement = new ArrayList<>(row.value());
                replacement.set(setColumnIndex, newValue);
                table.table().update(row.key(), List.copyOf(replacement), statementTx.context());
            }
            finishStatementTransaction(statementTx);
            return VersionedStorageSqlResult.updateCount(rows.size());
        } catch (RuntimeException | SQLException e) {
            failStatementTransaction(transactionOwner, statementTx);
            throw e;
        }
    }

    private static VersionedStorageSqlResult deleteWhereEquals(
            TableDefinition table,
            String whereColumnName,
            String rawWhereValue,
            Object transactionOwner,
            boolean autoCommit,
            int transactionIsolation) throws SQLException {
        int whereColumnIndex = table.columnIndex(whereColumnName);
        Object whereValue = table.columns().get(whereColumnIndex).parseValue(rawWhereValue.trim());
        StatementTransaction statementTx = beginStatementTransaction(table, transactionOwner, autoCommit, transactionIsolation);
        try {
            List<VersionedRow<Long, List<Object>>> rows = table.indexedRows(whereColumnIndex, whereValue, statementTx.context());
            for (VersionedRow<Long, List<Object>> row : rows) {
                table.table().delete(row.key(), statementTx.context());
            }
            finishStatementTransaction(statementTx);
            return VersionedStorageSqlResult.updateCount(rows.size());
        } catch (RuntimeException | SQLException e) {
            failStatementTransaction(transactionOwner, statementTx);
            throw e;
        }
    }

    private static VersionedStorageSqlResult selectAll(
            TableDefinition table,
            String orderColumnName,
            String orderDirection,
            Object transactionOwner,
            boolean autoCommit,
            int transactionIsolation) throws SQLException {
        StatementTransaction statementTx = beginStatementTransaction(table, transactionOwner, autoCommit, transactionIsolation);
        try {
            VersionedTableStats tableStats = table.table().stats(statementTx.context().currentView());
            long tableScanCost = estimateTableScanCost(tableStats);
            AccessPathSelection selection;
            if (orderColumnName == null) {
                List<List<Object>> rows = scanAllRows(table, statementTx);
                selection = new AccessPathSelection(rows, new VersionedStorageAccessPath(
                        table.metadata().qualifiedName(),
                        "select-all",
                        VersionedStorageAccessPath.TABLE_SCAN,
                        "",
                        "",
                        tableStats.visibleRowCount(),
                        tableStats.physicalVersionCount(),
                        tableStats.deadVersionEstimate(),
                        0L,
                        rows.size(),
                        tableScanCost,
                        0L));
            } else {
                selection = selectAllOrdered(table, orderColumnName, orderDirection, statementTx, tableStats, tableScanCost);
            }

            CachedRowSet rowSet = newRowSet(table.columns());
            List<List<Object>> rows = selection.rows();
            // CachedRowSet inserts each new row before the current cursor row.
            // Insert in reverse so callers observe provider/index order.
            for (int i = rows.size() - 1; i >= 0; i--) {
                append(rowSet, rows.get(i), table.columns());
            }
            lastAccessPath = selection.accessPath();
            rowSet.beforeFirst();
            finishStatementTransaction(statementTx);
            return VersionedStorageSqlResult.rows(rowSet);
        } catch (RuntimeException | SQLException e) {
            failStatementTransaction(transactionOwner, statementTx);
            throw e;
        }
    }

    private static AccessPathSelection selectAllOrdered(
            TableDefinition table,
            String orderColumnName,
            String orderDirection,
            StatementTransaction statementTx,
            VersionedTableStats tableStats,
            long tableScanCost) throws SQLException {
        int orderColumnIndex = table.columnIndex(orderColumnName);
        boolean descending = orderDirection != null && "DESC".equalsIgnoreCase(orderDirection.trim());
        Optional<IndexDefinition> indexDefinition = table.indexDefinitionForColumn(orderColumnIndex);
        if (indexDefinition.isPresent()) {
            IndexDefinition index = indexDefinition.get();
            VersionedIndexStats indexStats = index.index().statsRange(null, true, null, true, statementTx.context().currentView());
            List<List<Object>> rows = valuesFrom(index.index().lookupRange(null, true, null, true, statementTx.context().currentView()));
            if (descending) {
                java.util.Collections.reverse(rows);
            }
            return new AccessPathSelection(rows, new VersionedStorageAccessPath(
                    table.metadata().qualifiedName(),
                    descending ? "select-order-desc" : "select-order",
                    VersionedStorageAccessPath.INDEX_SCAN,
                    table.columns().get(orderColumnIndex).name(),
                    index.metadata().indexName(),
                    tableStats.visibleRowCount(),
                    tableStats.physicalVersionCount(),
                    tableStats.deadVersionEstimate(),
                    indexStats.candidateCount(),
                    indexStats.visibleMatchCount(),
                    tableScanCost,
                    indexStats.estimatedLookupCost()));
        }

        List<List<Object>> rows = scanAllRows(table, statementTx);
        sortRows(rows, orderColumnIndex, descending);
        return new AccessPathSelection(rows, new VersionedStorageAccessPath(
                table.metadata().qualifiedName(),
                descending ? "select-order-desc" : "select-order",
                VersionedStorageAccessPath.TABLE_SCAN,
                table.columns().get(orderColumnIndex).name(),
                "",
                tableStats.visibleRowCount(),
                tableStats.physicalVersionCount(),
                tableStats.deadVersionEstimate(),
                0L,
                rows.size(),
                tableScanCost,
                0L));
    }

    private static List<List<Object>> scanAllRows(
            TableDefinition table,
            StatementTransaction statementTx) {
        List<List<Object>> rows = new ArrayList<>();
        try (VersionedScan<Long, List<Object>> scan = table.table().openScan(statementTx.context().currentView())) {
            while (scan.next()) {
                VersionedRow<Long, List<Object>> row = scan.row();
                rows.add(row.value());
            }
        }
        return rows;
    }

    private static void sortRows(List<List<Object>> rows, int columnIndex, boolean descending) {
        rows.sort((left, right) -> comparePredicateValues(left.get(columnIndex), right.get(columnIndex)));
        if (descending) {
            java.util.Collections.reverse(rows);
        }
    }

    private static VersionedStorageSqlResult selectCount(
            TableDefinition table,
            Object transactionOwner,
            boolean autoCommit,
            int transactionIsolation) throws SQLException {
        StatementTransaction statementTx = beginStatementTransaction(table, transactionOwner, autoCommit, transactionIsolation);
        try {
            VersionedTableStats tableStats = table.table().stats(statementTx.context().currentView());
            long visibleRows = tableStats.visibleRowCount();
            CachedRowSet rowSet = newRowSet(List.of(new ColumnDefinition("1", Types.INTEGER, "INTEGER", false, false)));
            append(rowSet, List.of(Math.toIntExact(visibleRows)), List.of(new ColumnDefinition("1", Types.INTEGER, "INTEGER", false, false)));
            lastAccessPath = new VersionedStorageAccessPath(
                    table.metadata().qualifiedName(),
                    "select-count",
                    VersionedStorageAccessPath.TABLE_SCAN,
                    "",
                    "",
                    tableStats.visibleRowCount(),
                    tableStats.physicalVersionCount(),
                    tableStats.deadVersionEstimate(),
                    0L,
                    visibleRows,
                    estimateTableScanCost(tableStats),
                    0L);
            rowSet.beforeFirst();
            finishStatementTransaction(statementTx);
            return VersionedStorageSqlResult.rows(rowSet);
        } catch (RuntimeException | SQLException e) {
            failStatementTransaction(transactionOwner, statementTx);
            throw e;
        }
    }

    private static CachedRowSet newRowSet(List<ColumnDefinition> columns) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            ColumnDefinition column = columns.get(i);
            int jdbcIndex = i + 1;
            metadata.setColumnName(jdbcIndex, column.name());
            metadata.setColumnLabel(jdbcIndex, column.name());
            metadata.setColumnType(jdbcIndex, column.jdbcType());
            metadata.setColumnTypeName(jdbcIndex, column.typeName());
            metadata.setNullable(jdbcIndex, java.sql.ResultSetMetaData.columnNullable);
        }
        rowSet.setMetaData(metadata);
        return rowSet;
    }

    private static void append(CachedRowSet rowSet, List<Object> values, List<ColumnDefinition> columns) throws SQLException {
        rowSet.moveToInsertRow();
        for (int i = 0; i < columns.size(); i++) {
            rowSet.updateObject(i + 1, values.get(i));
        }
        rowSet.insertRow();
        rowSet.moveToCurrentRow();
    }

    private static Optional<TableDefinition> findTable(String tableName) {
        VersionedTableMetadata metadata = TableIdentity.parse(tableName).metadata();
        synchronized (LOCK) {
            return Optional.ofNullable(TABLES.get(metadata));
        }
    }

    private static VersionedStorageProvider provider() throws SQLException {
        synchronized (LOCK) {
            if (cachedProvider == null) {
                cachedProvider = pageBackedStorageDirectory == null
                        ? discoverProvider()
                        : openPageBackedProvider(pageBackedStorageDirectory);
            }
            return cachedProvider;
        }
    }

    private static VersionedStorageProvider discoverProvider() throws SQLException {
        return VersionedStorageProviderDiscovery.discover()
                .stream()
                .filter(candidate -> PROVIDER_NAME.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> sqlException("0A000", "VersionedStorageProvider not discovered: " + PROVIDER_NAME));
    }

    private static VersionedStorageProvider openPageBackedProvider(Path storageDirectory) throws SQLException {
        try {
            Class<?> providerClass = Class.forName("io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider");
            Method openPageBacked = providerClass.getMethod("openPageBacked", Path.class);
            Object provider = openPageBacked.invoke(null, storageDirectory);
            if (provider instanceof VersionedStorageProvider versionedProvider) {
                return versionedProvider;
            }
            throw sqlException("X0MV4", "openPageBacked did not return a VersionedStorageProvider");
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            throw sqlException("X0MV4", "Could not open page-backed delos_mvcc provider: " + e.getMessage());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw sqlException("X0MV4", "Could not open page-backed delos_mvcc provider: " + cause.getMessage());
        }
    }

    private static void reopenTablesWithProvider(VersionedStorageProvider provider) throws SQLException {
        if (TABLES.isEmpty()) {
            return;
        }
        Map<VersionedTableMetadata, TableDefinition> reopened = new HashMap<>();
        for (TableDefinition existing : TABLES.values()) {
            reopened.put(existing.metadata(), existing.reopenWithProvider(provider));
        }
        TABLES.clear();
        TABLES.putAll(reopened);
    }

    /** Completes the active provider-local MVCC transaction for a Derby connection. */
    public static void commit(Object transactionOwner) throws SQLException {
        completeSessionTransaction(transactionOwner, true);
    }

    /** Rolls back the active provider-local MVCC transaction for a Derby connection. */
    public static void rollback(Object transactionOwner) throws SQLException {
        completeSessionTransaction(transactionOwner, false);
    }

    private static StatementTransaction beginStatementTransaction(
            TableDefinition table,
            Object transactionOwner,
            boolean autoCommit,
            int transactionIsolation) throws SQLException {
        if (autoCommit || transactionOwner == null) {
            return new StatementTransaction(table.coordinator(), table.coordinator().begin(), true);
        }

        SnapshotSemantics semantics = SnapshotSemantics.fromJdbcIsolation(transactionIsolation);
        synchronized (LOCK) {
            SessionTransaction session = SESSION_TRANSACTIONS.get(transactionOwner);
            if (session == null) {
                session = new SessionTransaction(table.coordinator(), table.coordinator().begin(), semantics);
                SESSION_TRANSACTIONS.put(transactionOwner, session);
            } else if (session.coordinator() != table.coordinator()) {
                throw sqlException("0A000", "A delos_mvcc SQL transaction cannot span multiple provider coordinators");
            } else if (session.snapshotSemantics() != semantics) {
                throw sqlException("25001", "Cannot change delos_mvcc snapshot semantics inside an active transaction");
            }

            TxContext statementContext = session.snapshotSemantics().freshSnapshotPerStatement()
                    ? session.coordinator().refresh(session.context())
                    : session.context();
            return new StatementTransaction(session.coordinator(), statementContext, false);
        }
    }

    private static void finishStatementTransaction(StatementTransaction statementTx) throws SQLException {
        if (statementTx.autoCommit()) {
            try {
                statementTx.coordinator().commit(statementTx.context());
                completeUniqueReservations(statementTx.context().transactionId(), true);
            } catch (RuntimeException e) {
                completeUniqueReservations(statementTx.context().transactionId(), false);
                throw sqlException("X0MV1", "Could not commit delos_mvcc statement transaction: " + e.getMessage());
            }
        }
    }

    private static void failStatementTransaction(Object transactionOwner, StatementTransaction statementTx) throws SQLException {
        if (statementTx.autoCommit()) {
            abort(statementTx);
            return;
        }
        completeSessionTransaction(transactionOwner, false);
    }

    private static void completeSessionTransaction(Object transactionOwner, boolean commit) throws SQLException {
        if (transactionOwner == null) {
            return;
        }
        SessionTransaction session;
        synchronized (LOCK) {
            session = SESSION_TRANSACTIONS.remove(transactionOwner);
        }
        if (session == null) {
            return;
        }
        try {
            if (commit) {
                session.coordinator().commit(session.context());
                completeUniqueReservations(session.context().transactionId(), true);
            } else {
                session.coordinator().abort(session.context());
                completeUniqueReservations(session.context().transactionId(), false);
            }
        } catch (RuntimeException e) {
            if (!commit) {
                completeUniqueReservations(session.context().transactionId(), false);
            }
            throw sqlException("X0MV1", "Could not " + (commit ? "commit" : "rollback")
                    + " delos_mvcc transaction: " + e.getMessage());
        }
    }

    private static void abort(StatementTransaction statementTx) throws SQLException {
        try {
            statementTx.coordinator().abort(statementTx.context());
            completeUniqueReservations(statementTx.context().transactionId(), false);
        } catch (RuntimeException e) {
            completeUniqueReservations(statementTx.context().transactionId(), false);
            throw sqlException("X0MV1", "Could not abort delos_mvcc statement transaction: " + e.getMessage());
        }
    }

    private static void completeUniqueReservations(long transactionId, boolean commit) {
        synchronized (LOCK) {
            for (TableDefinition table : TABLES.values()) {
                table.completeUniqueReservations(transactionId, commit);
            }
        }
    }

    private static List<ColumnDefinition> parseColumns(String columnList) throws SQLException {
        List<String> parts = splitCommaSeparated(columnList);
        List<ColumnDefinition> columns = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            Matcher matcher = Pattern.compile("(?is)^([a-zA-Z_][a-zA-Z0-9_]*)\\s+(.+)$").matcher(trimmed);
            if (!matcher.matches()) {
                throw sqlException("42X01", "Unsupported delos_mvcc column definition: " + trimmed);
            }
            columns.add(ColumnDefinition.parse(matcher.group(1), matcher.group(2)));
        }
        return List.copyOf(columns);
    }

    private static List<Object> parseValues(String valueList, List<ColumnDefinition> columns) throws SQLException {
        List<String> rawValues = splitCommaSeparated(valueList);
        if (rawValues.size() != columns.size()) {
            throw sqlException("42802", "INSERT value count does not match delos_mvcc table column count");
        }
        List<Object> values = new ArrayList<>(rawValues.size());
        for (int i = 0; i < rawValues.size(); i++) {
            values.add(columns.get(i).parseValue(rawValues.get(i).trim()));
        }
        return java.util.Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<String> splitCommaSeparated(String text) throws SQLException {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                current.append(ch);
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    current.append(text.charAt(++i));
                } else {
                    inString = !inString;
                }
            } else if (ch == ',' && !inString) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (inString) {
            throw sqlException("42X01", "Unterminated string literal in delos_mvcc SQL");
        }
        parts.add(current.toString());
        return parts;
    }

    private static String stripTerminator(String sql) {
        String trimmed = Objects.requireNonNull(sql, "sql").trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static SQLException sqlException(String sqlState, String message) {
        return new SQLException(message, sqlState);
    }

    private record PredicateRange(
            Object lowerBound,
            boolean lowerInclusive,
            Object upperBound,
            boolean upperInclusive) {
        private static PredicateRange parse(
                ColumnDefinition column,
                String operator,
                String rawLeftValue,
                String rawRightValue) throws SQLException {
            String normalized = operator.trim().toUpperCase(Locale.ROOT);
            if ("BETWEEN".equals(normalized)) {
                Object lower = column.parseValue(rawLeftValue.trim());
                Object upper = column.parseValue(Objects.requireNonNull(rawRightValue, "rawRightValue").trim());
                return new PredicateRange(lower, true, upper, true);
            }

            Object value = column.parseValue(rawLeftValue.trim());
            return switch (normalized) {
                case ">" -> new PredicateRange(value, false, null, true);
                case ">=" -> new PredicateRange(value, true, null, true);
                case "<" -> new PredicateRange(null, true, value, false);
                case "<=" -> new PredicateRange(null, true, value, true);
                default -> throw sqlException("42X01", "Unsupported delos_mvcc range predicate operator: " + operator);
            };
        }

        private boolean matches(Object value) {
            if (value == null) {
                return false;
            }
            if (lowerBound != null) {
                int lowerCompare = comparePredicateValues(value, lowerBound);
                if (lowerCompare < 0 || (lowerCompare == 0 && !lowerInclusive)) {
                    return false;
                }
            }
            if (upperBound != null) {
                int upperCompare = comparePredicateValues(value, upperBound);
                if (upperCompare > 0 || (upperCompare == 0 && !upperInclusive)) {
                    return false;
                }
            }
            return true;
        }
    }

    private record AccessPathSelection(
            List<List<Object>> rows,
            VersionedStorageAccessPath accessPath) {
        private AccessPathSelection {
            rows = List.copyOf(rows);
            accessPath = Objects.requireNonNull(accessPath, "accessPath");
        }
    }

    private record SessionTransaction(
            VersionedTransactionCoordinator coordinator,
            TxContext context,
            SnapshotSemantics snapshotSemantics) {
    }

    private enum SnapshotSemantics {
        FRESH_STATEMENT,
        STABLE_TRANSACTION;

        private boolean freshSnapshotPerStatement() {
            return this == FRESH_STATEMENT;
        }

        private static SnapshotSemantics fromJdbcIsolation(int transactionIsolation) throws SQLException {
            return switch (transactionIsolation) {
                case Connection.TRANSACTION_READ_UNCOMMITTED, Connection.TRANSACTION_READ_COMMITTED -> FRESH_STATEMENT;
                case Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE -> STABLE_TRANSACTION;
                default -> throw sqlException("X0MV3", "Unsupported delos_mvcc transaction isolation level: " + transactionIsolation);
            };
        }
    }

    private record StatementTransaction(
            VersionedTransactionCoordinator coordinator,
            TxContext context,
            boolean autoCommit) {
    }

    private static final class TableDefinition {
        private final VersionedTableMetadata metadata;
        private final List<ColumnDefinition> columns;
        private final List<UniqueConstraint> uniqueConstraints;
        private final Map<UniqueKey, UniqueReservation> uniqueReservations = new HashMap<>();
        private final Map<String, IndexDefinition> indexesByName = new HashMap<>();
        private final Map<Integer, IndexDefinition> indexesByColumnIndex = new HashMap<>();
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

        private VersionedTableMetadata metadata() {
            return metadata;
        }

        private List<ColumnDefinition> columns() {
            return columns;
        }

        private VersionedTable<Long, List<Object>> table() {
            return table;
        }

        private VersionedTransactionCoordinator coordinator() {
            return coordinator;
        }

        private synchronized void createIndex(VersionedIndexMetadata metadata, int columnIndex, TxContext buildContext) throws SQLException {
            String indexName = metadata.indexName();
            if (indexesByName.containsKey(indexName)) {
                throw sqlException("X0Y32", "delos_mvcc index already exists: " + metadata.qualifiedName());
            }
            if (indexesByColumnIndex.containsKey(columnIndex)) {
                throw sqlException("X0MV2", "delos_mvcc Phase 8 supports one provider-owned index per column: "
                        + columns.get(columnIndex).name());
            }
            VersionedIndex<Long, List<Object>> index = table.createIndex(
                    metadata,
                    row -> row.get(columnIndex),
                    buildContext.currentView());
            IndexDefinition definition = new IndexDefinition(metadata, columnIndex, index);
            indexesByName.put(indexName, definition);
            indexesByColumnIndex.put(columnIndex, definition);
        }

        private synchronized VersionedIndex<Long, List<Object>> indexForColumn(int columnIndex) throws SQLException {
            IndexDefinition definition = indexesByColumnIndex.get(columnIndex);
            if (definition == null) {
                throw sqlException("0A000", "delos_mvcc Phase 8 requires a provider-owned index on column "
                        + columns.get(columnIndex).name() + " before indexed lookup");
            }
            return definition.index();
        }

        private synchronized Optional<IndexDefinition> indexDefinitionForColumn(int columnIndex) {
            return Optional.ofNullable(indexesByColumnIndex.get(columnIndex));
        }

        private boolean isUniqueLookupColumn(int columnIndex) {
            ColumnDefinition column = columns.get(columnIndex);
            return column.primaryKey() || column.unique();
        }

        private TableDefinition reopenWithProvider(VersionedStorageProvider provider) throws SQLException {
            VersionedTransactionCoordinator reopenedCoordinator = provider.transactionCoordinator();
            VersionedTable<Long, List<Object>> reopenedTable = provider.createTable(metadata);
            TableDefinition reopened = new TableDefinition(metadata, columns, reopenedTable, reopenedCoordinator);
            for (IndexDefinition existingIndex : indexesByName.values()) {
                TxContext build = reopenedCoordinator.begin();
                try {
                    reopened.createIndex(existingIndex.metadata(), existingIndex.columnIndex(), build);
                    reopenedCoordinator.commit(build);
                } catch (RuntimeException | SQLException e) {
                    try {
                        reopenedCoordinator.abort(build);
                    } catch (RuntimeException abortFailure) {
                        e.addSuppressed(abortFailure);
                    }
                    throw e;
                }
            }
            return reopened;
        }

        private List<VersionedRow<Long, List<Object>>> indexedRows(
                int columnIndex,
                Object indexKey,
                TxContext context) throws SQLException {
            VersionedIndex<Long, List<Object>> index = indexForColumn(columnIndex);
            List<VersionedRow<Long, List<Object>>> rows = new ArrayList<>();
            try (VersionedScan<Long, List<Object>> scan = index.lookup(indexKey, context.currentView())) {
                while (scan.next()) {
                    rows.add(scan.row());
                }
            }
            return rows;
        }

        private int columnIndex(String columnName) throws SQLException {
            String normalized = columnName.trim().toUpperCase(Locale.ROOT);
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equals(normalized)) {
                    return i;
                }
            }
            throw sqlException("42X04", "Column not found in delos_mvcc table " + metadata.qualifiedName() + ": " + columnName);
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
                        throw sqlException("42X14", "delos_mvcc Phase 7 supports only one PRIMARY KEY column");
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

    private record TableIdentity(String schemaName, String tableName) {
        static TableIdentity parse(String sqlName) {
            String[] parts = sqlName.trim().split("\\.", 2);
            if (parts.length == 1) {
                return new TableIdentity(DEFAULT_SCHEMA, parts[0]);
            }
            return new TableIdentity(parts[0], parts[1]);
        }

        VersionedTableMetadata metadata() {
            return new VersionedTableMetadata(schemaName, tableName);
        }
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

        Object parseValue(String raw) throws SQLException {
            if (raw.equalsIgnoreCase("NULL")) {
                return null;
            }
            if (jdbcType == Types.INTEGER) {
                try {
                    return Integer.valueOf(raw);
                } catch (NumberFormatException e) {
                    throw sqlException("22018", "Invalid INTEGER value for delos_mvcc column " + name + ": " + raw);
                }
            }
            if (jdbcType == Types.VARCHAR || jdbcType == Types.CHAR) {
                if (raw.length() < 2 || raw.charAt(0) != '\'' || raw.charAt(raw.length() - 1) != '\'') {
                    throw sqlException("22018", "String value for delos_mvcc column " + name + " must be quoted");
                }
                return raw.substring(1, raw.length() - 1).replace("''", "'");
            }
            throw sqlException("0A000", "Unsupported delos_mvcc JDBC type: " + jdbcType);
        }
    }
}
