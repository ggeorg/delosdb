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
 * MODULE7C smoke: inherited MVCC DELETE WHERE equality is selective.
 *
 * <p>This is a runtime behavior proof, not a source audit. MODULE7A owns the
 * source-gated qualifier map. This smoke proves that normal Derby SQL DELETE
 * over an MVCC physical table mutates only rows delivered by the qualified MVCC
 * scan, and that commit/rollback behavior remains durable across a real Derby
 * shutdown/reopen.</p>
 */
public final class Module7cInheritedSqlDeleteWhereEqualitySmoke {
    private static final String DATABASE_PATH = "build/module7c-inherited-sql-delete-where-equality-db";
    private static final String DELETE_COMMIT_TABLE = "MODULE7C_DELETE_COMMIT";
    private static final String DELETE_ROLLBACK_TABLE = "MODULE7C_DELETE_ROLLBACK";
    private static final String DELETE_PREPARED_TABLE = "MODULE7C_DELETE_PREPARED";
    private static final String HEAP_TABLE = "MODULE7C_HEAP";

    private Module7cInheritedSqlDeleteWhereEqualitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        try {
            assertRuntimeDeleteWhereEquality();
            forceRealShutdownAndClearInMemoryState();
            reopenAndAssertDeleteWhereEqualityDurability();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertRuntimeDeleteWhereEquality() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();
        MvccScanController.resetQualifierRejectCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            createHeapCompatibilityFixture(statement);
            SmokeUtils.assertEquals(1, statement.executeUpdate(
                    "DELETE FROM APP." + HEAP_TABLE + " WHERE id = 2"),
                    "MODULE7C heap DELETE WHERE equality must delete exactly one row");
            SmokeUtils.assertEquals(List.of(1, 3), ids(statement, HEAP_TABLE),
                    "MODULE7C heap DELETE WHERE equality must leave non-matching rows intact");

            createMvccTable(statement, DELETE_COMMIT_TABLE);
            createMvccTable(statement, DELETE_ROLLBACK_TABLE);
            createMvccTable(statement, DELETE_PREPARED_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_COMMIT_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_ROLLBACK_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_PREPARED_TABLE);

            seedThreeRows(statement, DELETE_COMMIT_TABLE, "commit");
            seedThreeRows(statement, DELETE_ROLLBACK_TABLE, "rollback");
            seedThreeRows(statement, DELETE_PREPARED_TABLE, "prepared");

            SmokeUtils.assertEquals(1, statement.executeUpdate(
                    "DELETE FROM APP." + DELETE_COMMIT_TABLE + " WHERE id = 2"),
                    "MODULE7C committed MVCC DELETE WHERE id = 2 must report one deleted row");
            SmokeUtils.assertEquals(List.of(1, 3), ids(statement, DELETE_COMMIT_TABLE),
                    "MODULE7C committed MVCC DELETE WHERE id = 2 must leave rows 1 and 3 visible");
            SmokeUtils.assertEquals(List.of("commit-one", "commit-three"), names(statement, DELETE_COMMIT_TABLE),
                    "MODULE7C committed MVCC DELETE WHERE id = 2 must not delete non-matching values");

            connection.setAutoCommit(false);
            SmokeUtils.assertEquals(1, statement.executeUpdate(
                    "DELETE FROM APP." + DELETE_ROLLBACK_TABLE + " WHERE id = 2"),
                    "MODULE7C rolled-back MVCC DELETE WHERE id = 2 must report one candidate row");
            connection.rollback();
            connection.setAutoCommit(true);
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, DELETE_ROLLBACK_TABLE),
                    "MODULE7C rolled-back MVCC DELETE WHERE id = 2 must keep all rows visible");
            SmokeUtils.assertEquals(List.of("rollback-one", "rollback-three", "rollback-two"),
                    names(statement, DELETE_ROLLBACK_TABLE),
                    "MODULE7C rolled-back MVCC DELETE WHERE id = 2 must preserve row values");

            try (PreparedStatement deletePrepared = connection.prepareStatement(
                    "DELETE FROM APP." + DELETE_PREPARED_TABLE + " WHERE id = ?")) {
                deletePrepared.setInt(1, 3);
                SmokeUtils.assertEquals(1, deletePrepared.executeUpdate(),
                        "MODULE7C prepared MVCC DELETE WHERE id = ? must report one deleted row for id 3");
            }
            SmokeUtils.assertEquals(List.of(1, 2), ids(statement, DELETE_PREPARED_TABLE),
                    "MODULE7C prepared MVCC DELETE WHERE id = ? must leave rows 1 and 2 visible");
            SmokeUtils.assertEquals(List.of("prepared-one", "prepared-two"), names(statement, DELETE_PREPARED_TABLE),
                    "MODULE7C prepared MVCC DELETE WHERE id = ? must not delete non-matching values");

            require(MvccConglomerateController.deleteCountForTesting() > 0,
                    "MODULE7C DELETE WHERE equality must reach MvccConglomerateController through inherited RowChanger");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE7C DELETE WHERE equality must reach MvccScanController through inherited TableScanResultSet");
            require(MvccScanController.qualifierRejectCountForTesting() > 0,
                    "MODULE7C DELETE WHERE equality must reject non-matching rows at the MVCC qualifier boundary");
        }
    }

    private static void createHeapCompatibilityFixture(Statement statement) throws Exception {
        statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
        statement.executeUpdate("CREATE INDEX MODULE7C_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
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

    private static void forceRealShutdownAndClearInMemoryState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();
        MvccScanController.resetQualifierRejectCountForTesting();
    }

    private static void reopenAndAssertDeleteWhereEqualityDurability() throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertMvccPhysicalConglomerate(statement, DELETE_COMMIT_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_ROLLBACK_TABLE);
            assertMvccPhysicalConglomerate(statement, DELETE_PREPARED_TABLE);

            SmokeUtils.assertEquals(List.of(1, 3), ids(statement, DELETE_COMMIT_TABLE),
                    "MODULE7C committed MVCC DELETE WHERE id = 2 must survive Derby shutdown/reopen");
            SmokeUtils.assertEquals(List.of("commit-one", "commit-three"), names(statement, DELETE_COMMIT_TABLE),
                    "MODULE7C committed MVCC DELETE WHERE id = 2 must preserve non-matching values after restart");
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, DELETE_ROLLBACK_TABLE),
                    "MODULE7C rolled-back MVCC DELETE WHERE id = 2 must keep all rows visible after restart");
            SmokeUtils.assertEquals(List.of("rollback-one", "rollback-three", "rollback-two"),
                    names(statement, DELETE_ROLLBACK_TABLE),
                    "MODULE7C rolled-back MVCC DELETE WHERE id = 2 must preserve row values after restart");
            SmokeUtils.assertEquals(List.of(1, 2), ids(statement, DELETE_PREPARED_TABLE),
                    "MODULE7C prepared MVCC DELETE WHERE id = ? must survive Derby shutdown/reopen");
            SmokeUtils.assertEquals(List.of("prepared-one", "prepared-two"), names(statement, DELETE_PREPARED_TABLE),
                    "MODULE7C prepared MVCC DELETE WHERE id = ? must preserve non-matching values after restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE7C post-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            SmokeUtils.assertEquals(List.of(1, 3), ids(statement, HEAP_TABLE),
                    "MODULE7C heap and btree compatibility must stay green after MVCC DELETE WHERE equality restart proof");
        }
    }

    private static void assertMvccPhysicalConglomerate(Statement statement, String tableName) throws Exception {
        SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                baseConglomerateNumber(statement, tableName) & 0x0fL,
                "MODULE7C " + tableName + " must use an MVCC physical conglomerate");
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
        try (ResultSet rows = statement.executeQuery("SELECT id FROM APP." + tableName)) {
            List<Integer> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getInt(1));
            }
            values.sort(Integer::compareTo);
            return List.copyOf(values);
        }
    }

    private static List<String> names(Statement statement, String tableName) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT name FROM APP." + tableName)) {
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
