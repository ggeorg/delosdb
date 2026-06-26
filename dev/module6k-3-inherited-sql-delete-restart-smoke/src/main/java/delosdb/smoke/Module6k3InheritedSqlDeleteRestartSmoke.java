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
 * MODULE6K-3 smoke: inherited SQL DELETE restart hardening for MVCC physical
 * tables. This is deliberately DELETE-only. UPDATE, predicates, and indexes
 * on MVCC tables are out of scope.
 */
public final class Module6k3InheritedSqlDeleteRestartSmoke {
    private static final String DATABASE_PATH = "build/module6k-3-inherited-sql-delete-restart-db";
    private static final String DELETE_COMMIT_TABLE = "MODULE6K3_DELETE_COMMIT";
    private static final String DELETE_ROLLBACK_TABLE = "MODULE6K3_DELETE_ROLLBACK";
    private static final String HEAP_TABLE = "MODULE6K3_HEAP";

    private Module6k3InheritedSqlDeleteRestartSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        try {
            createAndMutateThroughInheritedSqlDelete();
            forceRealShutdownAndClearInMemoryState();
            reopenAndAssertInheritedSqlDeleteDurability();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void createAndMutateThroughInheritedSqlDelete() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6K3_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
            statement.executeUpdate("DELETE FROM APP." + HEAP_TABLE + " WHERE id = 1");
            SmokeUtils.assertEquals(List.of(), ids(statement, HEAP_TABLE),
                    "MODULE6K-3 heap DELETE and btree compatibility must stay green before restart");

            statement.executeUpdate("CREATE TABLE APP." + DELETE_COMMIT_TABLE
                    + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            statement.executeUpdate("CREATE TABLE APP." + DELETE_ROLLBACK_TABLE
                    + "(id INT, name VARCHAR(32)) USING delos_mvcc");

            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, DELETE_COMMIT_TABLE) & 0x0fL,
                    "MODULE6K-3 committed DELETE table must have an MVCC physical conglomerate");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, DELETE_ROLLBACK_TABLE) & 0x0fL,
                    "MODULE6K-3 rolled-back DELETE table must have an MVCC physical conglomerate");

            statement.executeUpdate("INSERT INTO APP." + DELETE_COMMIT_TABLE + " VALUES (101, 'delete-commit')");
            statement.executeUpdate("INSERT INTO APP." + DELETE_ROLLBACK_TABLE + " VALUES (202, 'delete-rollback')");

            connection.setAutoCommit(false);
            statement.executeUpdate("DELETE FROM APP." + DELETE_COMMIT_TABLE);
            connection.commit();
            connection.setAutoCommit(true);
            require(MvccConglomerateController.deleteCountForTesting() > 0,
                    "MODULE6K-3 committed SQL DELETE must reach MvccConglomerateController through inherited RowChanger");

            connection.setAutoCommit(false);
            statement.executeUpdate("DELETE FROM APP." + DELETE_ROLLBACK_TABLE);
            connection.rollback();
            connection.setAutoCommit(true);

            SmokeUtils.assertEquals(List.of(), ids(statement, DELETE_COMMIT_TABLE),
                    "MODULE6K-3 committed inherited SQL DELETE must hide the row before restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE6K-3 pre-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            SmokeUtils.assertEquals(List.of(202), ids(statement, DELETE_ROLLBACK_TABLE),
                    "MODULE6K-3 rolled-back inherited SQL DELETE must keep the row visible before restart");
            SmokeUtils.assertEquals(List.of("delete-rollback"), names(statement, DELETE_ROLLBACK_TABLE),
                    "MODULE6K-3 rolled-back inherited SQL DELETE must preserve row values before restart");
        }
    }

    private static void forceRealShutdownAndClearInMemoryState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void reopenAndAssertInheritedSqlDeleteDurability() throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, DELETE_COMMIT_TABLE) & 0x0fL,
                    "MODULE6K-3 committed DELETE table must still be an MVCC physical conglomerate after restart");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, DELETE_ROLLBACK_TABLE) & 0x0fL,
                    "MODULE6K-3 rolled-back DELETE table must still be an MVCC physical conglomerate after restart");

            SmokeUtils.assertEquals(List.of(), ids(statement, DELETE_COMMIT_TABLE),
                    "MODULE6K-3 committed inherited SQL DELETE must survive Derby shutdown/reopen");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE6K-3 post-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            SmokeUtils.assertEquals(List.of(202), ids(statement, DELETE_ROLLBACK_TABLE),
                    "MODULE6K-3 rolled-back inherited SQL DELETE must keep the row visible after restart");
            SmokeUtils.assertEquals(List.of("delete-rollback"), names(statement, DELETE_ROLLBACK_TABLE),
                    "MODULE6K-3 rolled-back inherited SQL DELETE must preserve row values after restart");
            SmokeUtils.assertEquals(List.of(), ids(statement, HEAP_TABLE),
                    "MODULE6K-3 heap and btree compatibility must stay green after MVCC restart proof");
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
