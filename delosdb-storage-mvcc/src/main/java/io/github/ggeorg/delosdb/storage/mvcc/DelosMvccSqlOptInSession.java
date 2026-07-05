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
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIsolationLevel;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

/**
 * Narrow SQL-shaped bridge for the experimental opt-in MVCC storage path.
 *
 * <p>This is not Derby SQL execution and it is deliberately not wired to the
 * default heap/store path. MVCC-12 uses it to prove the provider can execute a
 * tiny create/insert/select/update/delete lifecycle through the same opt-in
 * adapter and transaction coordinator that a future Derby SQL bridge will use.
 * MVCC-14 adds a primary-key/index lookup shape so index candidates are also
 * forced through MVCC visibility checks before SQL integration gets wider.</p>
 *
 * <p><strong>MODULE5A bridge status:</strong> proof-only. Current role:
 * regex SQL-shaped smoke harness for the MVCC provider lifecycle. Replacement
 * path: Derby-visible MVCC table identity plus Derby execution/store-access
 * provider dispatch. Delete after: normal Derby SELECT/INSERT/DELETE/UPDATE
 * over MVCC-backed tables no longer require this class.</p>
 */
public final class DelosMvccSqlOptInSession {
    private static final String DEFAULT_SCHEMA = "APP";
    public static final String ISOLATION_LEVEL_PROPERTY = "delosdb.mvcc.sql.isolation";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)^CREATE\\s+TABLE\\s+([A-Z][A-Z0-9_]*)\\s*\\(\\s*ID\\s+INT\\s*,\\s*NAME\\s+VARCHAR\\s*\\(\\s*20\\s*\\)\\s*\\)$");
    private static final Pattern CREATE_TABLE_WITH_PRIMARY_KEY = Pattern.compile(
            "(?i)^CREATE\\s+TABLE\\s+([A-Z][A-Z0-9_]*)\\s*\\(\\s*ID\\s+INT\\s+PRIMARY\\s+KEY\\s*,\\s*NAME\\s+VARCHAR\\s*\\(\\s*20\\s*\\)\\s*\\)$");
    private static final Pattern INSERT_VALUES = Pattern.compile(
            "(?i)^INSERT\\s+INTO\\s+([A-Z][A-Z0-9_]*)\\s+VALUES\\s*\\(\\s*(-?\\d+)\\s*,\\s*'([^']*)'\\s*\\)$");
    private static final Pattern SELECT_ALL = Pattern.compile(
            "(?i)^SELECT\\s+ID\\s*,\\s*NAME\\s+FROM\\s+([A-Z][A-Z0-9_]*)$");
    private static final Pattern SELECT_NAME_BY_ID = Pattern.compile(
            "(?i)^SELECT\\s+NAME\\s+FROM\\s+([A-Z][A-Z0-9_]*)\\s+WHERE\\s+ID\\s*=\\s*(-?\\d+)$");
    private static final Pattern SELECT_STAR_BY_ID = Pattern.compile(
            "(?i)^SELECT\\s+\\*\\s+FROM\\s+([A-Z][A-Z0-9_]*)\\s+WHERE\\s+ID\\s*=\\s*(-?\\d+)$");
    private static final Pattern UPDATE_NAME_BY_ID = Pattern.compile(
            "(?i)^UPDATE\\s+([A-Z][A-Z0-9_]*)\\s+SET\\s+NAME\\s*=\\s*'([^']*)'\\s+WHERE\\s+ID\\s*=\\s*(-?\\d+)$");
    private static final Pattern DELETE_BY_ID = Pattern.compile(
            "(?i)^DELETE\\s+FROM\\s+([A-Z][A-Z0-9_]*)\\s+WHERE\\s+ID\\s*=\\s*(-?\\d+)$");
    private static final Pattern COUNT_ALL = Pattern.compile(
            "(?i)^SELECT\\s+COUNT\\s*\\(\\s*\\*\\s*\\)\\s+FROM\\s+([A-Z][A-Z0-9_]*)$");

    private final VersionedStorageProvider provider;
    private final VersionedTransactionCoordinator transactions;
    private final VersionedIsolationLevel isolationLevel;
    private TxContext activeTransaction;
    private VersionedTableMetadata tableMetadata;
    private VersionedTable<Long, List<Object>> table;
    private VersionedIndex<Long, List<Object>> primaryKeyIndex;

    DelosMvccSqlOptInSession(VersionedStorageProvider provider, VersionedIsolationLevel isolationLevel) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.transactions = provider.transactionCoordinator();
        this.isolationLevel = Objects.requireNonNull(isolationLevel, "isolationLevel");
    }

    private DelosMvccSqlOptInSession(VersionedStorageProvider provider, Properties properties) {
        this(provider, VersionedIsolationLevel.fromPropertyValue(
                properties == null ? null : properties.getProperty(ISOLATION_LEVEL_PROPERTY)));
    }

    /** Opens the opt-in MVCC SQL-shaped session. The adapter enforces provider opt-in. */
    public static DelosMvccSqlOptInSession open(Properties properties) {
        return new DelosMvccSqlOptInSession(DelosMvccStoreAdapter.open(properties), properties);
    }

    /** Executes one statement from the MVCC-12 SQL subset. */
    public SqlResult execute(String sql) {
        String statement = normalizeStatement(sql);
        Matcher matcher;

        matcher = CREATE_TABLE_WITH_PRIMARY_KEY.matcher(statement);
        if (matcher.matches()) {
            return createTable(matcher.group(1), true);
        }

        matcher = CREATE_TABLE.matcher(statement);
        if (matcher.matches()) {
            return createTable(matcher.group(1), false);
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

        matcher = SELECT_STAR_BY_ID.matcher(statement);
        if (matcher.matches()) {
            return selectByPrimaryKeyIndex(matcher.group(1), Long.parseLong(matcher.group(2)));
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

    public VersionedIsolationLevel isolationLevel() {
        return isolationLevel;
    }

    public void beginTransaction() {
        if (activeTransaction != null) {
            throw new IllegalStateException("MVCC opt-in SQL smoke transaction is already active");
        }
        activeTransaction = transactions.begin();
    }

    public void commitTransaction() {
        TxContext transaction = requireActiveTransaction();
        transactions.commit(transaction);
        activeTransaction = null;
    }

    public void rollbackTransaction() {
        TxContext transaction = requireActiveTransaction();
        transactions.abort(transaction);
        activeTransaction = null;
    }

    private SqlResult createTable(String tableName, boolean primaryKey) {
        if (table != null) {
            throw new IllegalStateException("MVCC opt-in SQL smoke already has a table: "
                    + tableMetadata.qualifiedName());
        }
        tableMetadata = metadata(tableName);
        table = provider.createTable(tableMetadata);
        if (primaryKey) {
            TxContext build = transactions.begin();
            try {
                primaryKeyIndex = table.createIndex(
                        new VersionedIndexMetadata(tableMetadata, "PK_" + tableMetadata.tableName(), "ID", true),
                        row -> row.get(0),
                        build.currentView());
                transactions.commit(build);
            } catch (RuntimeException failure) {
                transactions.abort(build);
                throw failure;
            }
        }
        return SqlResult.updateCount(0);
    }

    private SqlResult insert(String tableName, long id, String name) {
        requireTable(tableName);
        TxContext transaction = statementTransaction();
        if (transaction != null) {
            table.insert(id, row(id, name), transaction);
            return SqlResult.updateCount(1);
        }
        return executeAutoTransaction(tx -> {
            table.insert(id, row(id, name), tx);
            return SqlResult.updateCount(1);
        });
    }

    private SqlResult update(String tableName, long id, String name) {
        requireTable(tableName);
        TxContext transaction = statementTransaction();
        if (transaction != null) {
            table.update(id, row(id, name), transaction);
            return SqlResult.updateCount(1);
        }
        return executeAutoTransaction(tx -> {
            table.update(id, row(id, name), tx);
            return SqlResult.updateCount(1);
        });
    }

    private SqlResult delete(String tableName, long id) {
        requireTable(tableName);
        TxContext transaction = statementTransaction();
        if (transaction != null) {
            table.delete(id, transaction);
            return SqlResult.updateCount(1);
        }
        return executeAutoTransaction(tx -> {
            table.delete(id, tx);
            return SqlResult.updateCount(1);
        });
    }

    private SqlResult selectAll(String tableName) {
        requireTable(tableName);
        TxContext transaction = statementTransaction();
        if (transaction != null) {
            return selectAllWith(transaction);
        }
        return executeReadOnlyAutoTransaction(this::selectAllWith);
    }

    private SqlResult selectAllWith(TxContext reader) {
        List<List<Object>> rows = new ArrayList<>();
        try (VersionedScan<Long, List<Object>> scan = table.openScan(reader.currentView())) {
            while (scan.next()) {
                VersionedRow<Long, List<Object>> row = scan.row();
                rows.add(List.copyOf(row.value()));
            }
        }
        return SqlResult.rows(rows);
    }

    private SqlResult selectNameById(String tableName, long id) {
        requireTable(tableName);
        TxContext transaction = statementTransaction();
        if (transaction != null) {
            return selectNameByIdWith(transaction, id);
        }
        return executeReadOnlyAutoTransaction(reader -> selectNameByIdWith(reader, id));
    }

    private SqlResult selectNameByIdWith(TxContext reader, long id) {
        Optional<List<Object>> row = table.read(id, reader.currentView());
        if (row.isEmpty()) {
            return SqlResult.rows(List.of());
        }
        return SqlResult.rows(List.of(List.of(row.get().get(1))));
    }

    private SqlResult selectByPrimaryKeyIndex(String tableName, long id) {
        requireTable(tableName);
        if (primaryKeyIndex == null) {
            throw new IllegalStateException("MVCC opt-in SQL smoke table has no primary-key index: "
                    + tableMetadata.qualifiedName());
        }
        TxContext transaction = statementTransaction();
        if (transaction != null) {
            return selectByPrimaryKeyIndexWith(transaction, id);
        }
        return executeReadOnlyAutoTransaction(reader -> selectByPrimaryKeyIndexWith(reader, id));
    }

    private SqlResult selectByPrimaryKeyIndexWith(TxContext reader, long id) {
        try (VersionedScan<Long, List<Object>> scan = primaryKeyIndex.lookup(Math.toIntExact(id), reader.currentView())) {
            List<List<Object>> rows = new ArrayList<>();
            while (scan.next()) {
                VersionedRow<Long, List<Object>> row = scan.row();
                rows.add(List.copyOf(row.value()));
            }
            return SqlResult.rows(rows);
        }
    }

    private SqlResult countAll(String tableName) {
        requireTable(tableName);
        TxContext transaction = statementTransaction();
        if (transaction != null) {
            return countAllWith(transaction);
        }
        return executeReadOnlyAutoTransaction(this::countAllWith);
    }

    private SqlResult countAllWith(TxContext reader) {
        return SqlResult.rows(List.of(List.of(table.stats(reader.currentView()).visibleRowCount())));
    }

    private TxContext requireActiveTransaction() {
        if (activeTransaction == null) {
            throw new IllegalStateException("MVCC opt-in SQL smoke transaction is not active");
        }
        return activeTransaction;
    }

    private TxContext statementTransaction() {
        if (activeTransaction == null) {
            return null;
        }
        activeTransaction = isolationLevel.statementContext(transactions, activeTransaction);
        return activeTransaction;
    }

    private SqlResult executeAutoTransaction(SqlOperation operation) {
        TxContext tx = transactions.begin();
        try {
            SqlResult result = operation.execute(tx);
            transactions.commit(tx);
            return result;
        } catch (RuntimeException failure) {
            transactions.abort(tx);
            throw failure;
        }
    }

    private SqlResult executeReadOnlyAutoTransaction(SqlOperation operation) {
        TxContext reader = transactions.begin();
        try {
            return operation.execute(reader);
        } finally {
            transactions.abort(reader);
        }
    }

    private interface SqlOperation {
        SqlResult execute(TxContext context);
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
