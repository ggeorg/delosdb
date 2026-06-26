package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE6K-4 smoke: runtime-only inherited MVCC CRUD restart audit.
 *
 * <p>This is deliberately an aggregate restart proof, not a source audit. It
 * does not grep Derby source or assert exact implementation strings. MODULE6A,
 * MODULE6I, and MODULE6J own source/audit guards.</p>
 */
public final class Module6k4InheritedSqlCrudRestartAuditSmoke {
    private static final String DATABASE_PATH = "build/module6k-4-inherited-sql-crud-restart-audit-db";

    private static final String INSERT_COMMIT_TABLE = "MODULE6K4_INSERT_COMMIT";
    private static final String INSERT_ROLLBACK_TABLE = "MODULE6K4_INSERT_ROLLBACK";
    private static final String UPDATE_COMMIT_TABLE = "MODULE6K4_UPDATE_COMMIT";
    private static final String UPDATE_ROLLBACK_TABLE = "MODULE6K4_UPDATE_ROLLBACK";
    private static final String DELETE_COMMIT_TABLE = "MODULE6K4_DELETE_COMMIT";
    private static final String DELETE_ROLLBACK_TABLE = "MODULE6K4_DELETE_ROLLBACK";
    private static final String HEAP_TABLE = "MODULE6K4_HEAP";

    private Module6k4InheritedSqlCrudRestartAuditSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        try {
            createAndMutateThroughInheritedSqlCrud();
            forceRealShutdownAndClearInMemoryState();
            reopenAndAssertInheritedSqlCrudDurability();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void createAndMutateThroughInheritedSqlCrud() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetInsertCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            createHeapCompatibilityFixture(statement);
            createMvccRestartAuditTables(statement);
            assertAllMvccPhysicalConglomerates(statement);

            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO APP." + INSERT_COMMIT_TABLE + " VALUES (101, 'insert-commit')");
            connection.commit();

            statement.executeUpdate("INSERT INTO APP." + INSERT_ROLLBACK_TABLE + " VALUES (102, 'insert-rollback')");
            connection.rollback();

            connection.setAutoCommit(true);
            statement.executeUpdate("INSERT INTO APP." + UPDATE_COMMIT_TABLE + " VALUES (201, 'update-before-commit')");
            statement.executeUpdate("INSERT INTO APP." + UPDATE_ROLLBACK_TABLE + " VALUES (202, 'update-before-rollback')");
            statement.executeUpdate("INSERT INTO APP." + DELETE_COMMIT_TABLE + " VALUES (301, 'delete-commit')");
            statement.executeUpdate("INSERT INTO APP." + DELETE_ROLLBACK_TABLE + " VALUES (302, 'delete-rollback')");

            connection.setAutoCommit(false);
            statement.executeUpdate("UPDATE APP." + UPDATE_COMMIT_TABLE + " SET name = 'update-after-commit'");
            connection.commit();

            statement.executeUpdate("UPDATE APP." + UPDATE_ROLLBACK_TABLE + " SET name = 'update-after-rollback'");
            connection.rollback();

            statement.executeUpdate("DELETE FROM APP." + DELETE_COMMIT_TABLE);
            connection.commit();

            statement.executeUpdate("DELETE FROM APP." + DELETE_ROLLBACK_TABLE);
            connection.rollback();
            connection.setAutoCommit(true);

            require(MvccConglomerateController.insertCountForTesting() > 0,
                    "MODULE6K-4 inherited SQL INSERT must reach MvccConglomerateController before restart");
            require(MvccConglomerateController.updateCountForTesting() > 0,
                    "MODULE6K-4 inherited SQL UPDATE must reach MvccConglomerateController before restart");
            require(MvccConglomerateController.deleteCountForTesting() > 0,
                    "MODULE6K-4 inherited SQL DELETE must reach MvccConglomerateController before restart");

            SmokeUtils.assertEquals(List.of("insert-commit"), names(statement, INSERT_COMMIT_TABLE),
                    "MODULE6K-4 committed INSERT must be visible before restart");
            SmokeUtils.assertEquals(List.of(), names(statement, INSERT_ROLLBACK_TABLE),
                    "MODULE6K-4 rolled-back INSERT must be invisible before restart");
            SmokeUtils.assertEquals(List.of("update-after-commit"), names(statement, UPDATE_COMMIT_TABLE),
                    "MODULE6K-4 committed UPDATE must be visible before restart");
            SmokeUtils.assertEquals(List.of(201), ids(statement, UPDATE_COMMIT_TABLE),
                    "MODULE6K-4 committed UPDATE must preserve non-updated columns before restart");
            SmokeUtils.assertEquals(List.of("update-before-rollback"), names(statement, UPDATE_ROLLBACK_TABLE),
                    "MODULE6K-4 rolled-back UPDATE must keep old value before restart");
            SmokeUtils.assertEquals(List.of(), ids(statement, DELETE_COMMIT_TABLE),
                    "MODULE6K-4 committed DELETE must hide the row before restart");
            SmokeUtils.assertEquals(List.of(302), ids(statement, DELETE_ROLLBACK_TABLE),
                    "MODULE6K-4 rolled-back DELETE must keep the row before restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE6K-4 pre-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            SmokeUtils.assertEquals(List.of("heap2"), names(statement, HEAP_TABLE),
                    "MODULE6K-4 heap and btree compatibility must stay green before restart");
        }
    }

    private static void createHeapCompatibilityFixture(Statement statement) throws Exception {
        statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
        statement.executeUpdate("CREATE INDEX MODULE6K4_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
        statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
        statement.executeUpdate("UPDATE APP." + HEAP_TABLE + " SET name = 'heap2' WHERE id = 1");
    }

    private static void createMvccRestartAuditTables(Statement statement) throws Exception {
        statement.executeUpdate("CREATE TABLE APP." + INSERT_COMMIT_TABLE
                + "(id INT, name VARCHAR(32)) USING delos_mvcc");
        statement.executeUpdate("CREATE TABLE APP." + INSERT_ROLLBACK_TABLE
                + "(id INT, name VARCHAR(32)) USING delos_mvcc");
        statement.executeUpdate("CREATE TABLE APP." + UPDATE_COMMIT_TABLE
                + "(id INT, name VARCHAR(32)) USING delos_mvcc");
        statement.executeUpdate("CREATE TABLE APP." + UPDATE_ROLLBACK_TABLE
                + "(id INT, name VARCHAR(32)) USING delos_mvcc");
        statement.executeUpdate("CREATE TABLE APP." + DELETE_COMMIT_TABLE
                + "(id INT, name VARCHAR(32)) USING delos_mvcc");
        statement.executeUpdate("CREATE TABLE APP." + DELETE_ROLLBACK_TABLE
                + "(id INT, name VARCHAR(32)) USING delos_mvcc");
    }

    private static void forceRealShutdownAndClearInMemoryState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerateController.resetInsertCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void reopenAndAssertInheritedSqlCrudDurability() throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertAllMvccPhysicalConglomerates(statement);

            SmokeUtils.assertEquals(List.of("insert-commit"), names(statement, INSERT_COMMIT_TABLE),
                    "MODULE6K-4 committed inherited SQL INSERT must survive Derby shutdown/reopen");
            SmokeUtils.assertEquals(List.of(), names(statement, INSERT_ROLLBACK_TABLE),
                    "MODULE6K-4 rolled-back inherited SQL INSERT must remain invisible after restart");
            SmokeUtils.assertEquals(List.of("update-after-commit"), names(statement, UPDATE_COMMIT_TABLE),
                    "MODULE6K-4 committed inherited SQL UPDATE must survive Derby shutdown/reopen");
            SmokeUtils.assertEquals(List.of(201), ids(statement, UPDATE_COMMIT_TABLE),
                    "MODULE6K-4 committed inherited SQL UPDATE must preserve non-updated columns after restart");
            SmokeUtils.assertEquals(List.of("update-before-rollback"), names(statement, UPDATE_ROLLBACK_TABLE),
                    "MODULE6K-4 rolled-back inherited SQL UPDATE must keep old values after restart");
            SmokeUtils.assertEquals(List.of(), ids(statement, DELETE_COMMIT_TABLE),
                    "MODULE6K-4 committed inherited SQL DELETE must survive Derby shutdown/reopen");
            SmokeUtils.assertEquals(List.of(302), ids(statement, DELETE_ROLLBACK_TABLE),
                    "MODULE6K-4 rolled-back inherited SQL DELETE must keep the row visible after restart");
            SmokeUtils.assertEquals(List.of("delete-rollback"), names(statement, DELETE_ROLLBACK_TABLE),
                    "MODULE6K-4 rolled-back inherited SQL DELETE must preserve row values after restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE6K-4 post-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            SmokeUtils.assertEquals(List.of("heap2"), names(statement, HEAP_TABLE),
                    "MODULE6K-4 heap and btree compatibility must stay green after MVCC restart audit");
        }
    }

    private static void assertAllMvccPhysicalConglomerates(Statement statement) throws Exception {
        assertMvccPhysicalConglomerate(statement, INSERT_COMMIT_TABLE);
        assertMvccPhysicalConglomerate(statement, INSERT_ROLLBACK_TABLE);
        assertMvccPhysicalConglomerate(statement, UPDATE_COMMIT_TABLE);
        assertMvccPhysicalConglomerate(statement, UPDATE_ROLLBACK_TABLE);
        assertMvccPhysicalConglomerate(statement, DELETE_COMMIT_TABLE);
        assertMvccPhysicalConglomerate(statement, DELETE_ROLLBACK_TABLE);
    }

    private static void assertMvccPhysicalConglomerate(Statement statement, String tableName) throws Exception {
        SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                baseConglomerateNumber(statement, tableName) & 0x0fL,
                "MODULE6K-4 " + tableName + " must use an MVCC physical conglomerate");
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
