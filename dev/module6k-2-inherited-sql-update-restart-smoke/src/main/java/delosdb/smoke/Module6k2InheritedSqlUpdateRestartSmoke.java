package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Files;
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
 * MODULE6K-2 smoke: inherited SQL UPDATE restart hardening for MVCC physical
 * tables.  This is deliberately UPDATE-only.  DELETE, predicates, and indexes
 * on MVCC tables are out of scope.
 */
public final class Module6k2InheritedSqlUpdateRestartSmoke {
    private static final String DATABASE_PATH = "build/module6k-2-inherited-sql-update-restart-db";
    private static final String UPDATE_COMMIT_TABLE = "MODULE6K2_UPDATE_COMMIT";
    private static final String UPDATE_ROLLBACK_TABLE = "MODULE6K2_UPDATE_ROLLBACK";
    private static final String HEAP_TABLE = "MODULE6K2_HEAP";

    private Module6k2InheritedSqlUpdateRestartSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        try {
            assertPlanDocumentsSmallRestartProofs();
            createAndMutateThroughInheritedSqlUpdate();
            forceRealShutdownAndClearInMemoryState();
            reopenAndAssertInheritedSqlUpdateDurability();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertPlanDocumentsSmallRestartProofs() throws Exception {
        String plan = Files.readString(Path.of("docs/storage/mvcc-inherited-correctness-hardening-plan.md"));
        requireContains(plan,
                "MODULE6K-2: inherited SQL `UPDATE` restart proof",
                "MODULE6K-2 plan must document the UPDATE restart proof");
        requireContains(plan,
                "force a Derby database shutdown",
                "MODULE6K-2 plan must require a real database shutdown, not just connection close");
        requireContains(plan,
                "Do not start indexes",
                "MODULE6K-2 plan must keep index work out of scope");
    }

    private static void createAndMutateThroughInheritedSqlUpdate() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6K2_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
            statement.executeUpdate("UPDATE APP." + HEAP_TABLE + " SET name = 'heap2' WHERE id = 1");
            SmokeUtils.assertEquals(List.of("heap2"), names(statement, HEAP_TABLE),
                    "MODULE6K-2 heap UPDATE and btree compatibility must stay green before restart");

            statement.executeUpdate("CREATE TABLE APP." + UPDATE_COMMIT_TABLE
                    + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            statement.executeUpdate("CREATE TABLE APP." + UPDATE_ROLLBACK_TABLE
                    + "(id INT, name VARCHAR(32)) USING delos_mvcc");

            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, UPDATE_COMMIT_TABLE) & 0x0fL,
                    "MODULE6K-2 committed UPDATE table must have an MVCC physical conglomerate");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, UPDATE_ROLLBACK_TABLE) & 0x0fL,
                    "MODULE6K-2 rolled-back UPDATE table must have an MVCC physical conglomerate");

            statement.executeUpdate("INSERT INTO APP." + UPDATE_COMMIT_TABLE + " VALUES (101, 'before-commit')");
            statement.executeUpdate("INSERT INTO APP." + UPDATE_ROLLBACK_TABLE + " VALUES (202, 'before-rollback')");

            connection.setAutoCommit(false);
            statement.executeUpdate("UPDATE APP." + UPDATE_COMMIT_TABLE + " SET name = 'after-commit'");
            connection.commit();
            connection.setAutoCommit(true);
            require(MvccConglomerateController.updateCountForTesting() > 0,
                    "MODULE6K-2 committed SQL UPDATE must reach MvccConglomerateController through inherited RowChanger");

            connection.setAutoCommit(false);
            statement.executeUpdate("UPDATE APP." + UPDATE_ROLLBACK_TABLE + " SET name = 'after-rollback'");
            connection.rollback();
            connection.setAutoCommit(true);

            SmokeUtils.assertEquals(List.of("after-commit"), names(statement, UPDATE_COMMIT_TABLE),
                    "MODULE6K-2 committed inherited SQL UPDATE must be visible before restart");
            SmokeUtils.assertEquals(List.of(101), ids(statement, UPDATE_COMMIT_TABLE),
                    "MODULE6K-2 inherited SQL UPDATE must preserve non-updated columns before restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE6K-2 pre-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            SmokeUtils.assertEquals(List.of("before-rollback"), names(statement, UPDATE_ROLLBACK_TABLE),
                    "MODULE6K-2 rolled-back inherited SQL UPDATE must keep old values before restart");
        }
    }

    private static void forceRealShutdownAndClearInMemoryState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void reopenAndAssertInheritedSqlUpdateDurability() throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, UPDATE_COMMIT_TABLE) & 0x0fL,
                    "MODULE6K-2 committed UPDATE table must still be an MVCC physical conglomerate after restart");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, UPDATE_ROLLBACK_TABLE) & 0x0fL,
                    "MODULE6K-2 rolled-back UPDATE table must still be an MVCC physical conglomerate after restart");

            SmokeUtils.assertEquals(List.of("after-commit"), names(statement, UPDATE_COMMIT_TABLE),
                    "MODULE6K-2 committed inherited SQL UPDATE must survive Derby shutdown/reopen");
            SmokeUtils.assertEquals(List.of(101), ids(statement, UPDATE_COMMIT_TABLE),
                    "MODULE6K-2 committed inherited SQL UPDATE must preserve non-updated columns after restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE6K-2 post-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            SmokeUtils.assertEquals(List.of("before-rollback"), names(statement, UPDATE_ROLLBACK_TABLE),
                    "MODULE6K-2 rolled-back inherited SQL UPDATE must keep old values after restart");
            SmokeUtils.assertEquals(List.of("heap2"), names(statement, HEAP_TABLE),
                    "MODULE6K-2 heap and btree compatibility must stay green after MVCC restart proof");
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

    private static void requireContains(String source, String expected, String label) {
        if (source == null || !source.contains(expected)) {
            throw new AssertionError(label + " expected source to contain: " + expected);
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
