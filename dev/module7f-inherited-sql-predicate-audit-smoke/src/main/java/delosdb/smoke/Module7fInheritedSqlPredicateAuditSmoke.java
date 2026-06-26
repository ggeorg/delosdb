package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE7F smoke: inherited MVCC predicate audit / compensation.
 *
 * <p>This is a runtime audit, not a source-string audit. MODULE7A owns the
 * source-gated predicate map. MODULE7F aggregates the predicate correctness
 * behavior proven in MODULE7B-E and verifies that selective and non-matching
 * predicates remain safe across a real Derby shutdown/reopen.</p>
 */
public final class Module7fInheritedSqlPredicateAuditSmoke {
    private static final String DATABASE_PATH = "build/module7f-inherited-sql-predicate-audit-db";
    private static final String SELECT_TABLE = "MODULE7F_SELECT_AUDIT";
    private static final String DELETE_MATCH_TABLE = "MODULE7F_DELETE_MATCH";
    private static final String DELETE_NONE_TABLE = "MODULE7F_DELETE_NONE";
    private static final String DELETE_ROLLBACK_TABLE = "MODULE7F_DELETE_ROLLBACK";
    private static final String UPDATE_MATCH_TABLE = "MODULE7F_UPDATE_MATCH";
    private static final String UPDATE_NONE_TABLE = "MODULE7F_UPDATE_NONE";
    private static final String UPDATE_ROLLBACK_TABLE = "MODULE7F_UPDATE_ROLLBACK";
    private static final String HEAP_TABLE = "MODULE7F_HEAP";

    private Module7fInheritedSqlPredicateAuditSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        try {
            assertRuntimePredicateAudit();
            forceRealShutdownAndClearInMemoryState();
            reopenAndAssertPredicateAuditDurability();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertRuntimePredicateAudit() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();
        MvccScanController.resetQualifierRejectCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            createHeapCompatibilityFixture(statement);

            createMvccTable(statement, SELECT_TABLE);
            createMvccTable(statement, DELETE_MATCH_TABLE);
            createMvccTable(statement, DELETE_NONE_TABLE);
            createMvccTable(statement, DELETE_ROLLBACK_TABLE);
            createMvccTable(statement, UPDATE_MATCH_TABLE);
            createMvccTable(statement, UPDATE_NONE_TABLE);
            createMvccTable(statement, UPDATE_ROLLBACK_TABLE);

            assertMvccPhysicalConglomerate(statement, SELECT_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_MATCH_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_NONE_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_ROLLBACK_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_MATCH_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_NONE_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_ROLLBACK_TABLE);

            seedThreeRows(statement, SELECT_TABLE, "select");
            seedThreeRows(statement, DELETE_MATCH_TABLE, "delete-match");
            seedThreeRows(statement, DELETE_NONE_TABLE, "delete-none");
            seedThreeRows(statement, DELETE_ROLLBACK_TABLE, "delete-rollback");
            seedThreeRows(statement, UPDATE_MATCH_TABLE, "update-match");
            seedThreeRows(statement, UPDATE_NONE_TABLE, "update-none");
            seedThreeRows(statement, UPDATE_ROLLBACK_TABLE, "update-rollback");

            SmokeUtils.assertEquals(List.of("2:select-two"), rows(statement, SELECT_TABLE, "WHERE id = 2"),
                    "MODULE7F SELECT WHERE id = 2 must return only the matching row");
            SmokeUtils.assertEquals(List.of(), rows(statement, SELECT_TABLE, "WHERE id = 999"),
                    "MODULE7F SELECT WHERE id = 999 must return no rows");

            SmokeUtils.assertEquals(1, statement.executeUpdate(
                    "DELETE FROM APP." + DELETE_MATCH_TABLE + " WHERE id = 2"),
                    "MODULE7F DELETE WHERE id = 2 must report one deleted row");
            SmokeUtils.assertEquals(List.of("1:delete-match-one", "3:delete-match-three"),
                    rows(statement, DELETE_MATCH_TABLE),
                    "MODULE7F DELETE WHERE id = 2 must not delete non-matching rows");

            SmokeUtils.assertEquals(0, statement.executeUpdate(
                    "DELETE FROM APP." + DELETE_NONE_TABLE + " WHERE id = 999"),
                    "MODULE7F DELETE WHERE id = 999 must report zero rows");
            assertRowsUnchanged(statement, DELETE_NONE_TABLE, "delete-none",
                    "MODULE7F DELETE WHERE id = 999 must be a no-op");

            SmokeUtils.assertEquals(1, statement.executeUpdate(
                    "UPDATE APP." + UPDATE_MATCH_TABLE + " SET name = 'update-match-two-updated' WHERE id = 2"),
                    "MODULE7F UPDATE WHERE id = 2 must report one updated row");
            SmokeUtils.assertEquals(List.of(
                    "1:update-match-one",
                    "2:update-match-two-updated",
                    "3:update-match-three"), rows(statement, UPDATE_MATCH_TABLE),
                    "MODULE7F UPDATE WHERE id = 2 must not update non-matching rows");

            SmokeUtils.assertEquals(0, statement.executeUpdate(
                    "UPDATE APP." + UPDATE_NONE_TABLE + " SET name = 'update-none-never' WHERE id = 999"),
                    "MODULE7F UPDATE WHERE id = 999 must report zero rows");
            assertRowsUnchanged(statement, UPDATE_NONE_TABLE, "update-none",
                    "MODULE7F UPDATE WHERE id = 999 must be a no-op");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1, statement.executeUpdate(
                        "DELETE FROM APP." + DELETE_ROLLBACK_TABLE + " WHERE id = 2"),
                        "MODULE7F rolled-back DELETE WHERE id = 2 must report one candidate row");
                SmokeUtils.assertEquals(1, statement.executeUpdate(
                        "UPDATE APP." + UPDATE_ROLLBACK_TABLE
                                + " SET name = 'update-rollback-two-updated' WHERE id = 2"),
                        "MODULE7F rolled-back UPDATE WHERE id = 2 must report one candidate row");
                connection.rollback();
                connection.setAutoCommit(true);
                assertRowsUnchanged(statement, DELETE_ROLLBACK_TABLE, "delete-rollback",
                        "MODULE7F rolled-back DELETE WHERE id = 2 must keep all rows visible");
                assertRowsUnchanged(statement, UPDATE_ROLLBACK_TABLE, "update-rollback",
                        "MODULE7F rolled-back UPDATE WHERE id = 2 must preserve old values");
            } finally {
                connection.setAutoCommit(true);
            }

            SmokeUtils.assertEquals(0, statement.executeUpdate(
                    "UPDATE APP." + HEAP_TABLE + " SET name = 'heap-never' WHERE id = 999"),
                    "MODULE7F heap non-matching UPDATE must report zero rows");
            SmokeUtils.assertEquals(1, statement.executeUpdate(
                    "DELETE FROM APP." + HEAP_TABLE + " WHERE id = 2"),
                    "MODULE7F heap DELETE WHERE id = 2 must still work");
            SmokeUtils.assertEquals(List.of("1:heap-one", "3:heap-three"), rows(statement, HEAP_TABLE),
                    "MODULE7F heap + btree compatibility must remain green");

            require(MvccConglomerateController.deleteCountForTesting() > 0,
                    "MODULE7F selective DELETE must reach MvccConglomerateController through inherited RowChanger");
            require(MvccConglomerateController.updateCountForTesting() > 0,
                    "MODULE7F selective UPDATE must reach MvccConglomerateController through inherited RowChanger");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE7F predicates must reach MvccScanController through inherited TableScanResultSet");
            require(MvccScanController.qualifierRejectCountForTesting() > 0,
                    "MODULE7F predicates must reject non-matching rows at the MVCC qualifier boundary");
        }
    }

    private static void forceRealShutdownAndClearInMemoryState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();
        MvccScanController.resetQualifierRejectCountForTesting();
    }

    private static void reopenAndAssertPredicateAuditDurability() throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertMvccPhysicalConglomerate(statement, SELECT_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_MATCH_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_NONE_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_ROLLBACK_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_MATCH_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_NONE_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_ROLLBACK_TABLE);

            SmokeUtils.assertEquals(List.of("2:select-two"), rows(statement, SELECT_TABLE, "WHERE id = 2"),
                    "MODULE7F SELECT WHERE id = 2 must remain selective after restart");
            SmokeUtils.assertEquals(List.of(), rows(statement, SELECT_TABLE, "WHERE id = 999"),
                    "MODULE7F SELECT WHERE id = 999 must remain empty after restart");
            SmokeUtils.assertEquals(List.of("1:delete-match-one", "3:delete-match-three"),
                    rows(statement, DELETE_MATCH_TABLE),
                    "MODULE7F committed DELETE WHERE id = 2 must survive restart");
            assertRowsUnchanged(statement, DELETE_NONE_TABLE, "delete-none",
                    "MODULE7F committed DELETE WHERE id = 999 no-op must survive restart");
            assertRowsUnchanged(statement, DELETE_ROLLBACK_TABLE, "delete-rollback",
                    "MODULE7F rolled-back DELETE WHERE id = 2 must remain invisible after restart");
            SmokeUtils.assertEquals(List.of(
                    "1:update-match-one",
                    "2:update-match-two-updated",
                    "3:update-match-three"), rows(statement, UPDATE_MATCH_TABLE),
                    "MODULE7F committed UPDATE WHERE id = 2 must survive restart");
            assertRowsUnchanged(statement, UPDATE_NONE_TABLE, "update-none",
                    "MODULE7F committed UPDATE WHERE id = 999 no-op must survive restart");
            assertRowsUnchanged(statement, UPDATE_ROLLBACK_TABLE, "update-rollback",
                    "MODULE7F rolled-back UPDATE WHERE id = 2 must remain invisible after restart");
            SmokeUtils.assertEquals(List.of("1:heap-one", "3:heap-three"), rows(statement, HEAP_TABLE),
                    "MODULE7F heap + btree compatibility must remain green after restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE7F post-restart audit SELECTs must reach MvccScanController");
        }
    }

    private static void createHeapCompatibilityFixture(Statement statement) throws Exception {
        statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
        statement.executeUpdate("CREATE INDEX MODULE7F_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
        statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap-one')");
        statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (2, 'heap-two')");
        statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (3, 'heap-three')");
    }

    private static void createMvccTable(Statement statement, String tableName) throws Exception {
        statement.executeUpdate("CREATE TABLE APP." + tableName
                + "(id INT, name VARCHAR(48)) USING delos_mvcc");
    }

    private static void seedThreeRows(Statement statement, String tableName, String prefix) throws Exception {
        statement.executeUpdate("INSERT INTO APP." + tableName + " VALUES (1, '" + prefix + "-one')");
        statement.executeUpdate("INSERT INTO APP." + tableName + " VALUES (2, '" + prefix + "-two')");
        statement.executeUpdate("INSERT INTO APP." + tableName + " VALUES (3, '" + prefix + "-three')");
    }

    private static void assertRowsUnchanged(Statement statement, String tableName, String prefix, String label) throws Exception {
        SmokeUtils.assertEquals(List.of(
                "1:" + prefix + "-one",
                "2:" + prefix + "-two",
                "3:" + prefix + "-three"), rows(statement, tableName), label);
    }

    private static void assertMvccPhysicalConglomerate(Statement statement, String tableName) throws Exception {
        SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                baseConglomerateNumber(statement, tableName) & 0x0fL,
                "MODULE7F " + tableName + " must use an MVCC physical conglomerate");
    }

    private static long baseConglomerateNumber(Statement statement, String tableName) throws Exception {
        String sql = "SELECT c.CONGLOMERATENUMBER "
                + "FROM SYS.SYSCONGLOMERATES c, SYS.SYSTABLES t "
                + "WHERE c.TABLEID = t.TABLEID "
                + "AND c.ISINDEX = FALSE "
                + "AND t.TABLENAME = '" + tableName + "'";
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new AssertionError("Missing base conglomerate for " + tableName);
            }
            long value = resultSet.getLong(1);
            if (resultSet.next()) {
                throw new AssertionError("More than one base conglomerate for " + tableName);
            }
            return value;
        }
    }

    private static List<String> rows(Statement statement, String tableName) throws Exception {
        return rows(statement, tableName, "");
    }

    private static List<String> rows(Statement statement, String tableName, String suffix) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT id, name FROM APP." + tableName + " " + suffix)) {
            List<Row> values = new ArrayList<>();
            while (resultSet.next()) {
                values.add(new Row(resultSet.getInt(1), resultSet.getString(2)));
            }
            values.sort(Comparator.comparingInt(Row::id));
            List<String> out = new ArrayList<>(values.size());
            for (Row row : values) {
                out.add(row.id() + ":" + row.name());
            }
            return List.copyOf(out);
        }
    }

    private static void clearNativeMvccProofProperties() {
        for (String propertyName : NativeMvccProofProperties.NAMES) {
            System.clearProperty(propertyName);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private record Row(int id, String name) {
    }

    private static final class NativeMvccProofProperties {
        private static final String[] NAMES = new String[] {
                "delosdb.storage.probe",
                "delosdb.storage.native.insert",
                "delosdb.storage.native.select.all",
                "delosdb.storage.native.select.eq",
                "delosdb.storage.native.select.range",
                "delosdb.storage.native.select.between",
                "delosdb.storage.native.select.null",
                "delosdb.storage.native.select.or",
                "delosdb.storage.native.select.projection.variants",
                "delosdb.storage.native.select.order.residual",
                "delosdb.storage.native.select.count",
                "delosdb.storage.native.delete.eq",
                "delosdb.storage.native.update.eq"
        };
    }
}
