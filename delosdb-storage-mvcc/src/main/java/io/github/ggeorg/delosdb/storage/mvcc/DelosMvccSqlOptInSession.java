package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

/**
 * Narrow SQL-shaped bridge for the experimental opt-in MVCC storage path.
 *
 * <p>This is not Derby SQL execution and it is deliberately not wired to the
 * default heap/store path. MVCC-12 uses it to prove the provider can execute a
 * tiny create/insert/select/update/delete lifecycle through the same opt-in
 * adapter and transaction coordinator that a future Derby SQL bridge will use.</p>
 */
public final class DelosMvccSqlOptInSession {
    private static final String DEFAULT_SCHEMA = "APP";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)^CREATE\\s+TABLE\\s+([A-Z][A-Z0-9_]*)\\s*\\(\\s*ID\\s+INT\\s*,\\s*NAME\\s+VARCHAR\\s*\\(\\s*20\\s*\\)\\s*\\)$");
    private static final Pattern INSERT_VALUES = Pattern.compile(
            "(?i)^INSERT\\s+INTO\\s+([A-Z][A-Z0-9_]*)\\s+VALUES\\s*\\(\\s*(-?\\d+)\\s*,\\s*'([^']*)'\\s*\\)$");
    private static final Pattern SELECT_ALL = Pattern.compile(
            "(?i)^SELECT\\s+ID\\s*,\\s*NAME\\s+FROM\\s+([A-Z][A-Z0-9_]*)$");
    private static final Pattern SELECT_NAME_BY_ID = Pattern.compile(
            "(?i)^SELECT\\s+NAME\\s+FROM\\s+([A-Z][A-Z0-9_]*)\\s+WHERE\\s+ID\\s*=\\s*(-?\\d+)$");
    private static final Pattern UPDATE_NAME_BY_ID = Pattern.compile(
            "(?i)^UPDATE\\s+([A-Z][A-Z0-9_]*)\\s+SET\\s+NAME\\s*=\\s*'([^']*)'\\s+WHERE\\s+ID\\s*=\\s*(-?\\d+)$");
    private static final Pattern DELETE_BY_ID = Pattern.compile(
            "(?i)^DELETE\\s+FROM\\s+([A-Z][A-Z0-9_]*)\\s+WHERE\\s+ID\\s*=\\s*(-?\\d+)$");
    private static final Pattern COUNT_ALL = Pattern.compile(
            "(?i)^SELECT\\s+COUNT\\s*\\(\\s*\\*\\s*\\)\\s+FROM\\s+([A-Z][A-Z0-9_]*)$");

    private final VersionedStorageProvider provider;
    private final VersionedTransactionCoordinator transactions;
    private VersionedTableMetadata tableMetadata;
    private VersionedTable<Long, List<Object>> table;

    private DelosMvccSqlOptInSession(VersionedStorageProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.transactions = provider.transactionCoordinator();
    }

    /** Opens the opt-in MVCC SQL-shaped session. The adapter enforces provider opt-in. */
    public static DelosMvccSqlOptInSession open(Properties properties) {
        return new DelosMvccSqlOptInSession(DelosMvccStoreAdapter.open(properties));
    }

    /** Executes one statement from the MVCC-12 SQL subset. */
    public SqlResult execute(String sql) {
        String statement = normalizeStatement(sql);
        Matcher matcher;

        matcher = CREATE_TABLE.matcher(statement);
        if (matcher.matches()) {
            return createTable(matcher.group(1));
        }

        matcher = INSERT_VALUES.matcher(statement);
        if (matcher.matches()) {
            return insert(matcher.group(1), Long.parseLong(matcher.group(2)), matcher.group(3));
        }

        matcher = SELECT_ALL.matcher(statement);
        if (matcher.matches()) {
            return selectAll(matcher.group(1));
        }

        matcher = UPDATE_NAME_BY_ID.matcher(statement);
        if (matcher.matches()) {
            return update(matcher.group(1), Long.parseLong(matcher.group(3)), matcher.group(2));
        }

        matcher = SELECT_NAME_BY_ID.matcher(statement);
        if (matcher.matches()) {
            return selectNameById(matcher.group(1), Long.parseLong(matcher.group(2)));
        }

        matcher = DELETE_BY_ID.matcher(statement);
        if (matcher.matches()) {
            return delete(matcher.group(1), Long.parseLong(matcher.group(2)));
        }

        matcher = COUNT_ALL.matcher(statement);
        if (matcher.matches()) {
            return countAll(matcher.group(1));
        }

        throw new UnsupportedOperationException("Unsupported MVCC opt-in SQL smoke statement: " + sql);
    }

    public List<SqlResult> executeAll(List<String> statements) {
        Objects.requireNonNull(statements, "statements");
        List<SqlResult> results = new ArrayList<>();
        for (String statement : statements) {
            results.add(execute(statement));
        }
        return List.copyOf(results);
    }

    public VersionedStorageProvider provider() {
        return provider;
    }

    private SqlResult createTable(String tableName) {
        if (table != null) {
            throw new IllegalStateException("MVCC opt-in SQL smoke already has a table: "
                    + tableMetadata.qualifiedName());
        }
        tableMetadata = metadata(tableName);
        table = provider.createTable(tableMetadata);
        return SqlResult.updateCount(0);
    }

    private SqlResult insert(String tableName, long id, String name) {
        requireTable(tableName);
        TxContext tx = transactions.begin();
        try {
            table.insert(id, row(id, name), tx);
            transactions.commit(tx);
            return SqlResult.updateCount(1);
        } catch (RuntimeException failure) {
            transactions.abort(tx);
            throw failure;
        }
    }

    private SqlResult update(String tableName, long id, String name) {
        requireTable(tableName);
        TxContext tx = transactions.begin();
        try {
            table.update(id, row(id, name), tx);
            transactions.commit(tx);
            return SqlResult.updateCount(1);
        } catch (RuntimeException failure) {
            transactions.abort(tx);
            throw failure;
        }
    }

    private SqlResult delete(String tableName, long id) {
        requireTable(tableName);
        TxContext tx = transactions.begin();
        try {
            table.delete(id, tx);
            transactions.commit(tx);
            return SqlResult.updateCount(1);
        } catch (RuntimeException failure) {
            transactions.abort(tx);
            throw failure;
        }
    }

    private SqlResult selectAll(String tableName) {
        requireTable(tableName);
        TxContext reader = transactions.begin();
        try {
            List<List<Object>> rows = new ArrayList<>();
            try (VersionedScan<Long, List<Object>> scan = table.openScan(reader.currentView())) {
                while (scan.next()) {
                    VersionedRow<Long, List<Object>> row = scan.row();
                    rows.add(List.copyOf(row.value()));
                }
            }
            return SqlResult.rows(rows);
        } finally {
            transactions.abort(reader);
        }
    }

    private SqlResult selectNameById(String tableName, long id) {
        requireTable(tableName);
        TxContext reader = transactions.begin();
        try {
            Optional<List<Object>> row = table.read(id, reader.currentView());
            if (row.isEmpty()) {
                return SqlResult.rows(List.of());
            }
            return SqlResult.rows(List.of(List.of(row.get().get(1))));
        } finally {
            transactions.abort(reader);
        }
    }

    private SqlResult countAll(String tableName) {
        requireTable(tableName);
        TxContext reader = transactions.begin();
        try {
            return SqlResult.rows(List.of(List.of(table.stats(reader.currentView()).visibleRowCount())));
        } finally {
            transactions.abort(reader);
        }
    }

    private void requireTable(String tableName) {
        String normalized = normalizeIdentifier(tableName);
        if (table == null) {
            throw new IllegalStateException("MVCC opt-in SQL smoke table has not been created: " + normalized);
        }
        if (!tableMetadata.tableName().equals(normalized)) {
            throw new IllegalArgumentException("MVCC opt-in SQL smoke expected table "
                    + tableMetadata.tableName() + " but got " + normalized);
        }
    }

    private static VersionedTableMetadata metadata(String tableName) {
        return new VersionedTableMetadata(DEFAULT_SCHEMA, normalizeIdentifier(tableName));
    }

    private static List<Object> row(long id, String name) {
        return List.of(Math.toIntExact(id), Objects.requireNonNull(name, "name"));
    }

    private static String normalizeStatement(String sql) {
        String statement = Objects.requireNonNull(sql, "sql").trim();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1).trim();
        }
        if (statement.isEmpty()) {
            throw new IllegalArgumentException("MVCC opt-in SQL statement must not be blank");
        }
        return statement;
    }

    private static String normalizeIdentifier(String identifier) {
        String normalized = Objects.requireNonNull(identifier, "identifier").trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("identifier must not be blank");
        }
        return normalized;
    }

    /** Result for one statement in the MVCC-12 SQL subset. */
    public record SqlResult(int updateCount, List<List<Object>> rows) {
        public SqlResult {
            rows = rows == null ? List.of() : copyRows(rows);
        }

        public static SqlResult updateCount(int count) {
            if (count < 0) {
                throw new IllegalArgumentException("update count must be non-negative");
            }
            return new SqlResult(count, List.of());
        }

        public static SqlResult rows(List<List<Object>> rows) {
            return new SqlResult(-1, rows);
        }

        public boolean hasRows() {
            return updateCount < 0;
        }

        private static List<List<Object>> copyRows(List<List<Object>> rows) {
            List<List<Object>> copied = new ArrayList<>();
            for (List<Object> row : rows) {
                copied.add(List.copyOf(Objects.requireNonNull(row, "row")));
            }
            return List.copyOf(copied);
        }
    }
}
