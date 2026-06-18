package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Post-A52 SQL bridge proof for A44 missing-history translation.
 *
 * <p>The kernel already proves {@code MvccHistoryPrunedException}. This smoke
 * proves the same condition crossing the Derby/JDBC bridge: a SELECT that hits
 * pruned MVCC history must fail as a declared {@link SQLException} with SQLState
 * {@code X0MV6}, and statement cleanup failures must be suppressed rather than
 * replacing the original pruned-history failure.</p>
 */
public final class VersionedStorageSqlHistoryPrunedSmoke {
    private static final String TABLE_NAME = "sql_history_pruned_mvcc";
    private static final String ID_INDEX = "shp_id_idx";
    private static final String HISTORY_PRUNED_SQL_STATE = "X0MV6";
    private static final String STATEMENT_CLEANUP_SQL_STATE = "X0MV1";
    private static final String HISTORY_PRUNED_CLASS =
            "io.github.ggeorg.delosdb.storage.mvcc.MvccHistoryPrunedException";

    private VersionedStorageSqlHistoryPrunedSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try {
            createTableAndSeed(databasePath);
            assertPrunedHistoryBecomesDeclaredSqlException(databasePath);
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB MVCC SQL history-pruned bridge smoke test passed.");
    }

    private static void createTableAndSeed(String databasePath) throws Exception {
        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + TABLE_NAME + "(id int primary key, name varchar(20)) using delos_mvcc");
            statement.executeUpdate("create index " + ID_INDEX + " on " + TABLE_NAME + "(id)");
            statement.executeUpdate("insert into " + TABLE_NAME + " values (1, 'old')");
        }
    }

    private static void assertPrunedHistoryBecomesDeclaredSqlException(String databasePath) throws Exception {
        try (Connection reader = SmokeUtils.connect(databasePath, false);
             Connection writer = SmokeUtils.connect(databasePath, false);
             Statement readerStatement = reader.createStatement();
             Statement writerStatement = writer.createStatement()) {
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertSingleName(readerStatement, "old", "repeatable-read reader pins original version");

            writer.setAutoCommit(false);
            writer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            writerStatement.executeUpdate("update " + TABLE_NAME + " set name = 'new' where id = 1");
            writer.commit();

            // This intentionally simulates the A44 unsafe-prune boundary at the SQL bridge layer:
            // the reader's stale statement context remains registered, but the provider cleanup
            // has pruned the history that context would need.
            VersionedStorageSqlBridge.forceUnsafeHistoryPruneForTesting(TABLE_NAME);

            SQLException failure = expectSqlState(
                    readerStatement,
                    "select * from " + TABLE_NAME + " where id = 1",
                    HISTORY_PRUNED_SQL_STATE,
                    "pruned history must cross the JDBC bridge as X0MV6");
            if (!hasCauseNamed(failure, HISTORY_PRUNED_CLASS)) {
                throw new AssertionError("X0MV6 should preserve MvccHistoryPrunedException as its cause chain: " + failure);
            }
            if (failure.getSuppressed().length == 0) {
                throw new AssertionError("X0MV6 should retain cleanup failure as suppressed exception");
            }
            Throwable suppressed = failure.getSuppressed()[0];
            if (!(suppressed instanceof SQLException suppressedSql)
                    || !STATEMENT_CLEANUP_SQL_STATE.equals(suppressedSql.getSQLState())) {
                throw new AssertionError("expected suppressed X0MV1 cleanup failure but was: " + suppressed);
            }
        }

        try (Connection verifier = SmokeUtils.connect(databasePath, false);
             Statement statement = verifier.createStatement()) {
            assertSingleName(statement, "new", "new snapshots still read retained current version");
        }
    }

    private static SQLException expectSqlState(
            Statement statement,
            String sql,
            String expectedState,
            String message) throws Exception {
        try (ResultSet ignored = statement.executeQuery(sql)) {
            throw new AssertionError(message + ": expected SQLState " + expectedState + " but query succeeded");
        } catch (SQLException e) {
            SmokeUtils.assertEquals(expectedState, e.getSQLState(), message);
            return e;
        }
    }

    private static void assertSingleName(Statement statement, String expectedName, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select * from " + TABLE_NAME + " where id = 1")) {
            if (!rs.next()) {
                throw new AssertionError(message + ": no row returned");
            }
            SmokeUtils.assertEquals("1", String.valueOf(rs.getInt(1)), message + " id");
            SmokeUtils.assertEquals(expectedName, rs.getString(2), message + " name");
            if (rs.next()) {
                throw new AssertionError(message + ": unexpected extra row");
            }
        }
    }

    private static boolean hasCauseNamed(Throwable failure, String className) {
        Throwable current = failure;
        while (current != null) {
            if (className.equals(current.getClass().getName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
