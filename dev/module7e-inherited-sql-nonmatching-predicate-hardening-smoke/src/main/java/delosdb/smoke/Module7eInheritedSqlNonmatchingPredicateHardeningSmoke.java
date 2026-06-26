package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE7E smoke: inherited MVCC non-matching predicates are safe no-ops.
 *
 * <p>This is a runtime behavior proof, not a source audit. It proves that
 * normal Derby SQL SELECT/DELETE/UPDATE over MVCC physical tables honors
 * non-matching equality predicates without accidentally returning or mutating
 * rows. It also proves the committed no-op state and rolled-back no-op state
 * remain unchanged after a real Derby shutdown/reopen.</p>
 */
public final class Module7eInheritedSqlNonmatchingPredicateHardeningSmoke {
    private static final String DATABASE_PATH = "build/module7e-inherited-sql-nonmatching-predicate-hardening-db";
    private static final String SELECT_TABLE = "MODULE7E_SELECT_NONE";
    private static final String DELETE_COMMIT_TABLE = "MODULE7E_DELETE_COMMIT_NONE";
    private static final String DELETE_ROLLBACK_TABLE = "MODULE7E_DELETE_ROLLBACK_NONE";
    private static final String UPDATE_COMMIT_TABLE = "MODULE7E_UPDATE_COMMIT_NONE";
    private static final String UPDATE_ROLLBACK_TABLE = "MODULE7E_UPDATE_ROLLBACK_NONE";
    private static final String PREPARED_TABLE = "MODULE7E_PREPARED_NONE";
    private static final String HEAP_TABLE = "MODULE7E_HEAP";

    private Module7eInheritedSqlNonmatchingPredicateHardeningSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        try {
            assertRuntimeNonMatchingPredicatesAreNoOps();
            forceRealShutdownAndClearInMemoryState();
            reopenAndAssertNonMatchingPredicateDurability();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertRuntimeNonMatchingPredicatesAreNoOps() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();
        MvccScanController.resetQualifierRejectCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            createHeapCompatibilityFixture(statement);
            SmokeUtils.assertEquals(0, statement.executeUpdate(
                    "UPDATE APP." + HEAP_TABLE + " SET name = 'heap-never' WHERE id = 999"),
                    "MODULE7E heap non-matching UPDATE must report zero rows");
            SmokeUtils.assertEquals(0, statement.executeUpdate(
                    "DELETE FROM APP." + HEAP_TABLE + " WHERE id = 999"),
                    "MODULE7E heap non-matching DELETE must report zero rows");
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, HEAP_TABLE),
                    "MODULE7E heap non-matching predicates must keep all rows visible");
            SmokeUtils.assertEquals(List.of("heap-one", "heap-three", "heap-two"), names(statement, HEAP_TABLE),
                    "MODULE7E heap non-matching predicates must keep values unchanged");

            createMvccTable(statement, SELECT_TABLE);
            createMvccTable(statement, DELETE_COMMIT_TABLE);
            createMvccTable(statement, DELETE_ROLLBACK_TABLE);
            createMvccTable(statement, UPDATE_COMMIT_TABLE);
            createMvccTable(statement, UPDATE_ROLLBACK_TABLE);
            createMvccTable(statement, PREPARED_TABLE);
            assertMvccPhysicalConglomerate(statement, SELECT_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_COMMIT_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_ROLLBACK_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_COMMIT_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_ROLLBACK_TABLE);
            assertMvccPhysicalConglomerate(statement, PREPARED_TABLE);

            seedThreeRows(statement, SELECT_TABLE, "select");
            seedThreeRows(statement, DELETE_COMMIT_TABLE, "delete-commit");
            seedThreeRows(statement, DELETE_ROLLBACK_TABLE, "delete-rollback");
            seedThreeRows(statement, UPDATE_COMMIT_TABLE, "update-commit");
            seedThreeRows(statement, UPDATE_ROLLBACK_TABLE, "update-rollback");
            seedThreeRows(statement, PREPARED_TABLE, "prepared");

            SmokeUtils.assertEquals(List.of(), ids(statement, SELECT_TABLE, "WHERE id = 999"),
                    "MODULE7E non-matching MVCC SELECT WHERE id = 999 must return no rows");
            SmokeUtils.assertEquals(List.of(), names(statement, SELECT_TABLE, "WHERE name = 'missing'"),
                    "MODULE7E non-matching MVCC SELECT WHERE name = 'missing' must return no rows");

            SmokeUtils.assertEquals(0, statement.executeUpdate(
                    "DELETE FROM APP." + DELETE_COMMIT_TABLE + " WHERE id = 999"),
                    "MODULE7E committed MVCC DELETE WHERE id = 999 must report zero rows");
            assertRowsUnchanged(statement, DELETE_COMMIT_TABLE, "delete-commit",
                    "MODULE7E committed MVCC DELETE WHERE id = 999 must not delete any rows");

            SmokeUtils.assertEquals(0, statement.executeUpdate(
                    "UPDATE APP." + UPDATE_COMMIT_TABLE + " SET name = 'update-commit-never' WHERE id = 999"),
                    "MODULE7E committed MVCC UPDATE WHERE id = 999 must report zero rows");
            assertRowsUnchanged(statement, UPDATE_COMMIT_TABLE, "update-commit",
                    "MODULE7E committed MVCC UPDATE WHERE id = 999 must not update any rows");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(0, statement.executeUpdate(
                        "DELETE FROM APP." + DELETE_ROLLBACK_TABLE + " WHERE id = 999"),
                        "MODULE7E rolled-back MVCC DELETE WHERE id = 999 must report zero rows");
                SmokeUtils.assertEquals(0, statement.executeUpdate(
                        "UPDATE APP." + UPDATE_ROLLBACK_TABLE + " SET name = 'update-rollback-never' WHERE id = 999"),
                        "MODULE7E rolled-back MVCC UPDATE WHERE id = 999 must report zero rows");
                connection.rollback();
                connection.setAutoCommit(true);
                assertRowsUnchanged(statement, DELETE_ROLLBACK_TABLE, "delete-rollback",
                        "MODULE7E rolled-back MVCC DELETE WHERE id = 999 must keep rows visible");
                assertRowsUnchanged(statement, UPDATE_ROLLBACK_TABLE, "update-rollback",
                        "MODULE7E rolled-back MVCC UPDATE WHERE id = 999 must keep values unchanged");
            } finally {
                connection.setAutoCommit(true);
            }

            try (PreparedStatement updatePrepared = connection.prepareStatement(
                    "UPDATE APP." + PREPARED_TABLE + " SET name = ? WHERE id = ?");
                 PreparedStatement deletePrepared = connection.prepareStatement(
                    "DELETE FROM APP." + PREPARED_TABLE + " WHERE id = ?")) {
                updatePrepared.setString(1, "prepared-never");
                updatePrepared.setInt(2, 999);
                SmokeUtils.assertEquals(0, updatePrepared.executeUpdate(),
                        "MODULE7E prepared MVCC UPDATE WHERE id = ? must report zero rows for non-matching id");
                deletePrepared.setInt(1, 998);
                SmokeUtils.assertEquals(0, deletePrepared.executeUpdate(),
                        "MODULE7E prepared MVCC DELETE WHERE id = ? must report zero rows for non-matching id");
            }
            assertRowsUnchanged(statement, PREPARED_TABLE, "prepared",
                    "MODULE7E prepared non-matching predicates must leave all rows unchanged");

            SmokeUtils.assertEquals(0, MvccConglomerateController.deleteCountForTesting(),
                    "MODULE7E non-matching DELETE predicates must not call MVCC delete on any row");
            SmokeUtils.assertEquals(0, MvccConglomerateController.updateCountForTesting(),
                    "MODULE7E non-matching UPDATE predicates must not call MVCC update on any row");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE7E non-matching predicates must reach MvccScanController through inherited TableScanResultSet");
            require(MvccScanController.qualifierRejectCountForTesting() > 0,
                    "MODULE7E non-matching predicates must reject rows at the MVCC qualifier boundary");
        }
    }

    private static void createHeapCompatibilityFixture(Statement statement) throws Exception {
        statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
        statement.executeUpdate("CREATE INDEX MODULE7E_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
        statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap-one')");
        statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (2, 'heap-two')");
        statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (3, 'heap-three')");
    }

    private static void createMvccTable(Statement statement, String tableName) throws Exception {
        statement.executeUpdate("CREATE TABLE APP." + tableName
                + "(id INT, name VARCHAR(32)) USING delos_mvcc");
    }

    private static void seedThreeRows(Statement statement, String tableName, String prefix) throws Exception {
        statement.executeUpdate("INSERT INTO APP." + tableName + " VALUES (1, '" + prefix + "-one')");
        statement.executeUpdate("INSERT INTO APP." + tableName + " VALUES (2, '" + prefix + "-two')");
        statement.executeUpdate("INSERT INTO APP." + tableName + " VALUES (3, '" + prefix + "-three')");
    }

    private static void assertRowsUnchanged(Statement statement, String tableName, String prefix, String label) throws Exception {
        SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, tableName), label + " / row identities");
        SmokeUtils.assertEquals(List.of(prefix + "-one", prefix + "-three", prefix + "-two"),
                names(statement, tableName), label + " / row values");
    }

    private static void forceRealShutdownAndClearInMemoryState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();
        MvccScanController.resetQualifierRejectCountForTesting();
    }

    private static void reopenAndAssertNonMatchingPredicateDurability() throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertMvccPhysicalConglomerate(statement, SELECT_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_COMMIT_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_ROLLBACK_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_COMMIT_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_ROLLBACK_TABLE);
            assertMvccPhysicalConglomerate(statement, PREPARED_TABLE);

            SmokeUtils.assertEquals(List.of(), ids(statement, SELECT_TABLE, "WHERE id = 999"),
                    "MODULE7E non-matching MVCC SELECT WHERE id = 999 must return no rows after restart");
            assertRowsUnchanged(statement, DELETE_COMMIT_TABLE, "delete-commit",
                    "MODULE7E committed MVCC DELETE WHERE id = 999 must leave rows unchanged after restart");
            assertRowsUnchanged(statement, DELETE_ROLLBACK_TABLE, "delete-rollback",
                    "MODULE7E rolled-back MVCC DELETE WHERE id = 999 must leave rows unchanged after restart");
            assertRowsUnchanged(statement, UPDATE_COMMIT_TABLE, "update-commit",
                    "MODULE7E committed MVCC UPDATE WHERE id = 999 must leave rows unchanged after restart");
            assertRowsUnchanged(statement, UPDATE_ROLLBACK_TABLE, "update-rollback",
                    "MODULE7E rolled-back MVCC UPDATE WHERE id = 999 must leave rows unchanged after restart");
            assertRowsUnchanged(statement, PREPARED_TABLE, "prepared",
                    "MODULE7E prepared non-matching predicates must leave rows unchanged after restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE7E post-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, HEAP_TABLE),
                    "MODULE7E heap and btree compatibility must stay green after non-matching predicate restart proof");
            SmokeUtils.assertEquals(List.of("heap-one", "heap-three", "heap-two"), names(statement, HEAP_TABLE),
                    "MODULE7E heap values must remain unchanged after restart");
        }
    }

    private static void assertMvccPhysicalConglomerate(Statement statement, String tableName) throws Exception {
        SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                baseConglomerateNumber(statement, tableName) & 0x0fL,
                "MODULE7E " + tableName + " must use an MVCC physical conglomerate");
    }

    private static long baseConglomerateNumber(Statement statement, String tableName) throws Exception {
        String sql = "SELECT c.CONGLOMERATENUMBER "
                + "FROM SYS.SYSCONGLOMERATES c, SYS.SYSTABLES t "
                + "WHERE c.TABLEID = t.TABLEID "
                + "AND c.ISINDEX = FALSE "
                + "AND t.TABLENAME = '" + tableName + "'";
        try (ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new AssertionError("Missing base conglomerate for " + tableName);
            }
            long value = rows.getLong(1);
            if (rows.next()) {
                throw new AssertionError("More than one base conglomerate for " + tableName);
            }
            return value;
        }
    }

    private static List<Integer> ids(Statement statement, String tableName) throws Exception {
        return ids(statement, tableName, "");
    }

    private static List<Integer> ids(Statement statement, String tableName, String suffix) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT id FROM APP." + tableName + " " + suffix)) {
            List<Integer> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getInt(1));
            }
            values.sort(Integer::compareTo);
            return List.copyOf(values);
        }
    }

    private static List<String> names(Statement statement, String tableName) throws Exception {
        return names(statement, tableName, "");
    }

    private static List<String> names(Statement statement, String tableName, String suffix) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT name FROM APP." + tableName + " " + suffix)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            values.sort(String::compareTo);
            return List.copyOf(values);
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
