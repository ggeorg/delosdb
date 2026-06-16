package io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.VersionedStorageProviderDiscovery;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
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
 * INSERT INTO name VALUES (1, 'alpha')
 * SELECT * FROM name
 * SELECT COUNT(*) FROM name
 * </pre>
 *
 * <p>Every write is committed through the provider-local transaction
 * coordinator. Mapping Derby transaction commit/rollback to MVCC is Phase 5.</p>
 */
@InternalApi
public final class VersionedStorageSqlBridge {
    private static final String PROVIDER_NAME = "delos_mvcc";
    private static final String DEFAULT_SCHEMA = "APP";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^create\\s+table\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s*\\((.*)\\)\\s+using\\s+delos_mvcc$");
    private static final Pattern INSERT_VALUES = Pattern.compile(
            "(?is)^insert\\s+into\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s+values\\s*\\((.*)\\)$");
    private static final Pattern SELECT_ALL = Pattern.compile(
            "(?is)^select\\s+\\*\\s+from\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)(?:\\s+order\\s+by\\s+[a-zA-Z_][a-zA-Z0-9_]*)?$");
    private static final Pattern SELECT_COUNT = Pattern.compile(
            "(?is)^select\\s+count\\s*\\(\\s*\\*\\s*\\)\\s+from\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)$");

    private static final Object LOCK = new Object();
    private static final Map<VersionedTableMetadata, TableDefinition> TABLES = new HashMap<>();

    private VersionedStorageSqlBridge() {
    }

    /**
     * Attempts to execute a supported experimental MVCC SQL statement.
     *
     * @return a result when this bridge handled the SQL, or {@code null} when
     *         normal Derby execution should continue.
     */
    public static VersionedStorageSqlResult tryExecute(String sql) throws SQLException {
        String normalizedSql = stripTerminator(sql);

        Matcher create = CREATE_TABLE.matcher(normalizedSql);
        if (create.matches()) {
            return createTable(create.group(1), create.group(2));
        }

        Matcher insert = INSERT_VALUES.matcher(normalizedSql);
        if (insert.matches()) {
            Optional<TableDefinition> table = findTable(insert.group(1));
            if (table.isPresent()) {
                return insertValues(table.get(), insert.group(2));
            }
            return null;
        }

        Matcher selectAll = SELECT_ALL.matcher(normalizedSql);
        if (selectAll.matches()) {
            Optional<TableDefinition> table = findTable(selectAll.group(1));
            if (table.isPresent()) {
                return selectAll(table.get());
            }
            return null;
        }

        Matcher selectCount = SELECT_COUNT.matcher(normalizedSql);
        if (selectCount.matches()) {
            Optional<TableDefinition> table = findTable(selectCount.group(1));
            if (table.isPresent()) {
                return selectCount(table.get());
            }
            return null;
        }

        return null;
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

    private static VersionedStorageSqlResult insertValues(TableDefinition table, String valueList) throws SQLException {
        List<Object> values = parseValues(valueList, table.columns());
        if (values.size() != table.columns().size()) {
            throw sqlException("42802", "INSERT value count does not match delos_mvcc table column count");
        }

        TxContext tx = table.coordinator().begin();
        try {
            table.table().insert(table.nextRowKey(), values, tx);
            table.coordinator().commit(tx);
            return VersionedStorageSqlResult.updateCount(1L);
        } catch (RuntimeException e) {
            table.coordinator().abort(tx);
            throw e;
        }
    }

    private static VersionedStorageSqlResult selectAll(TableDefinition table) throws SQLException {
        TxContext tx = table.coordinator().begin();
        try {
            CachedRowSet rowSet = newRowSet(table.columns());
            List<List<Object>> rows = new ArrayList<>();
            try (VersionedScan<Long, List<Object>> scan = table.table().openScan(tx.currentView())) {
                while (scan.next()) {
                    VersionedRow<Long, List<Object>> row = scan.row();
                    rows.add(row.value());
                }
            }
            // CachedRowSet inserts each new row before the current cursor row.
            // Insert in reverse so callers observe provider scan order.
            for (int i = rows.size() - 1; i >= 0; i--) {
                append(rowSet, rows.get(i), table.columns());
            }
            rowSet.beforeFirst();
            table.coordinator().commit(tx);
            return VersionedStorageSqlResult.rows(rowSet);
        } catch (RuntimeException | SQLException e) {
            table.coordinator().abort(tx);
            throw e;
        }
    }

    private static VersionedStorageSqlResult selectCount(TableDefinition table) throws SQLException {
        TxContext tx = table.coordinator().begin();
        try {
            long visibleRows = table.table().stats(tx.currentView()).visibleRowCount();
            CachedRowSet rowSet = newRowSet(List.of(new ColumnDefinition("1", Types.INTEGER, "INTEGER")));
            append(rowSet, List.of(Math.toIntExact(visibleRows)), List.of(new ColumnDefinition("1", Types.INTEGER, "INTEGER")));
            rowSet.beforeFirst();
            table.coordinator().commit(tx);
            return VersionedStorageSqlResult.rows(rowSet);
        } catch (RuntimeException | SQLException e) {
            table.coordinator().abort(tx);
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
        return VersionedStorageProviderDiscovery.discover()
                .stream()
                .filter(candidate -> PROVIDER_NAME.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> sqlException("0A000", "VersionedStorageProvider not discovered: " + PROVIDER_NAME));
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
        return List.copyOf(values);
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

    private static final class TableDefinition {
        private final VersionedTableMetadata metadata;
        private final List<ColumnDefinition> columns;
        private final VersionedTable<Long, List<Object>> table;
        private final VersionedTransactionCoordinator coordinator;
        private long nextKey = 1L;

        private TableDefinition(
                VersionedTableMetadata metadata,
                List<ColumnDefinition> columns,
                VersionedTable<Long, List<Object>> table,
                VersionedTransactionCoordinator coordinator) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            this.columns = List.copyOf(columns);
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

        private synchronized long nextRowKey() {
            return nextKey++;
        }
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

    private record ColumnDefinition(String name, int jdbcType, String typeName) {
        static ColumnDefinition parse(String name, String typeText) throws SQLException {
            String normalizedType = typeText.trim().toUpperCase(Locale.ROOT);
            if (normalizedType.equals("INT") || normalizedType.equals("INTEGER")) {
                return new ColumnDefinition(name.toUpperCase(Locale.ROOT), Types.INTEGER, "INTEGER");
            }
            if (normalizedType.matches("VARCHAR\\s*\\(\\s*[0-9]+\\s*\\)")) {
                return new ColumnDefinition(name.toUpperCase(Locale.ROOT), Types.VARCHAR, normalizedType.replaceAll("\\s+", ""));
            }
            if (normalizedType.matches("CHAR\\s*\\(\\s*[0-9]+\\s*\\)")) {
                return new ColumnDefinition(name.toUpperCase(Locale.ROOT), Types.CHAR, normalizedType.replaceAll("\\s+", ""));
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
