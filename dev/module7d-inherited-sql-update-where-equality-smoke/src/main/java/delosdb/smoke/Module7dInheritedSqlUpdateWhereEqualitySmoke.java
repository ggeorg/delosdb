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
 * MODULE7D smoke: inherited MVCC UPDATE WHERE equality is selective.
 *
 * <p>This is a runtime behavior proof, not a source audit. MODULE7A owns the
 * source-gated qualifier map. This smoke proves that normal Derby SQL UPDATE
 * over an MVCC physical table mutates only rows delivered by the qualified MVCC
 * scan, preserves non-updated columns, and keeps commit/rollback behavior
 * durable across a real Derby shutdown/reopen.</p>
 */
public final class Module7dInheritedSqlUpdateWhereEqualitySmoke {
    private static final String DATABASE_PATH = "build/module7d-inherited-sql-update-where-equality-db";
    private static final String UPDATE_COMMIT_TABLE = "MODULE7D_UPDATE_COMMIT";
    private static final String UPDATE_ROLLBACK_TABLE = "MODULE7D_UPDATE_ROLLBACK";
    private static final String UPDATE_PREPARED_TABLE = "MODULE7D_UPDATE_PREPARED";
    private static final String HEAP_TABLE = "MODULE7D_HEAP";

    private Module7dInheritedSqlUpdateWhereEqualitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        try {
            assertRuntimeUpdateWhereEquality();
            forceRealShutdownAndClearInMemoryState();
            reopenAndAssertUpdateWhereEqualityDurability();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertRuntimeUpdateWhereEquality() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();
        MvccScanController.resetQualifierRejectCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            createHeapCompatibilityFixture(statement);
            SmokeUtils.assertEquals(1, statement.executeUpdate(
                    "UPDATE APP." + HEAP_TABLE + " SET name = 'heap-two-updated' WHERE id = 2"),
                    "MODULE7D heap UPDATE WHERE equality must update exactly one row");
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, HEAP_TABLE),
                    "MODULE7D heap UPDATE WHERE equality must keep all row identities visible");
            SmokeUtils.assertEquals(List.of("heap-one", "heap-three", "heap-two-updated"),
                    names(statement, HEAP_TABLE),
                    "MODULE7D heap UPDATE WHERE equality must update only the matching value");

            createMvccTable(statement, UPDATE_COMMIT_TABLE);
            createMvccTable(statement, UPDATE_ROLLBACK_TABLE);
            createMvccTable(statement, UPDATE_PREPARED_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_COMMIT_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_ROLLBACK_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_PREPARED_TABLE);

            seedThreeRows(statement, UPDATE_COMMIT_TABLE, "commit");
            seedThreeRows(statement, UPDATE_ROLLBACK_TABLE, "rollback");
            seedThreeRows(statement, UPDATE_PREPARED_TABLE, "prepared");

            SmokeUtils.assertEquals(1, statement.executeUpdate(
                    "UPDATE APP." + UPDATE_COMMIT_TABLE + " SET name = 'commit-two-updated' WHERE id = 2"),
                    "MODULE7D committed MVCC UPDATE WHERE id = 2 must report one updated row");
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, UPDATE_COMMIT_TABLE),
                    "MODULE7D committed MVCC UPDATE WHERE id = 2 must preserve all row identities");
            SmokeUtils.assertEquals(List.of("commit-one", "commit-three", "commit-two-updated"),
                    names(statement, UPDATE_COMMIT_TABLE),
                    "MODULE7D committed MVCC UPDATE WHERE id = 2 must update only row 2");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1, statement.executeUpdate(
                        "UPDATE APP." + UPDATE_ROLLBACK_TABLE + " SET name = 'rollback-two-updated' WHERE id = 2"),
                        "MODULE7D rolled-back MVCC UPDATE WHERE id = 2 must report one candidate row");
                connection.rollback();
                connection.setAutoCommit(true);
                SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, UPDATE_ROLLBACK_TABLE),
                        "MODULE7D rolled-back MVCC UPDATE WHERE id = 2 must keep all rows visible");
                SmokeUtils.assertEquals(List.of("rollback-one", "rollback-three", "rollback-two"),
                        names(statement, UPDATE_ROLLBACK_TABLE),
                        "MODULE7D rolled-back MVCC UPDATE WHERE id = 2 must preserve old values");
            } finally {
                connection.setAutoCommit(true);
            }

            try (PreparedStatement updatePrepared = connection.prepareStatement(
                    "UPDATE APP." + UPDATE_PREPARED_TABLE + " SET name = ? WHERE id = ?")) {
                updatePrepared.setString(1, "prepared-three-updated");
                updatePrepared.setInt(2, 3);
                SmokeUtils.assertEquals(1, updatePrepared.executeUpdate(),
                        "MODULE7D prepared MVCC UPDATE WHERE id = ? must report one updated row for id 3");
            }
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, UPDATE_PREPARED_TABLE),
                    "MODULE7D prepared MVCC UPDATE WHERE id = ? must preserve all row identities");
            SmokeUtils.assertEquals(List.of("prepared-one", "prepared-three-updated", "prepared-two"),
                    names(statement, UPDATE_PREPARED_TABLE),
                    "MODULE7D prepared MVCC UPDATE WHERE id = ? must not update non-matching values");

            require(MvccConglomerateController.updateCountForTesting() > 0,
                    "MODULE7D UPDATE WHERE equality must reach MvccConglomerateController through inherited RowChanger");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE7D UPDATE WHERE equality must reach MvccScanController through inherited TableScanResultSet");
            require(MvccScanController.qualifierRejectCountForTesting() > 0,
                    "MODULE7D UPDATE WHERE equality must reject non-matching rows at the MVCC qualifier boundary");
        }
    }

    private static void createHeapCompatibilityFixture(Statement statement) throws Exception {
        statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
        statement.executeUpdate("CREATE INDEX MODULE7D_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
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
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();
        MvccScanController.resetQualifierRejectCountForTesting();
    }

    private static void reopenAndAssertUpdateWhereEqualityDurability() throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertMvccPhysicalConglomerate(statement, UPDATE_COMMIT_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_ROLLBACK_TABLE);
            assertMvccPhysicalConglomerate(statement, UPDATE_PREPARED_TABLE);

            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, UPDATE_COMMIT_TABLE),
                    "MODULE7D committed MVCC UPDATE WHERE id = 2 must preserve identities after restart");
            SmokeUtils.assertEquals(List.of("commit-one", "commit-three", "commit-two-updated"),
                    names(statement, UPDATE_COMMIT_TABLE),
                    "MODULE7D committed MVCC UPDATE WHERE id = 2 must survive Derby shutdown/reopen");
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, UPDATE_ROLLBACK_TABLE),
                    "MODULE7D rolled-back MVCC UPDATE WHERE id = 2 must keep all rows visible after restart");
            SmokeUtils.assertEquals(List.of("rollback-one", "rollback-three", "rollback-two"),
                    names(statement, UPDATE_ROLLBACK_TABLE),
                    "MODULE7D rolled-back MVCC UPDATE WHERE id = 2 must preserve old values after restart");
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, UPDATE_PREPARED_TABLE),
                    "MODULE7D prepared MVCC UPDATE WHERE id = ? must preserve identities after restart");
            SmokeUtils.assertEquals(List.of("prepared-one", "prepared-three-updated", "prepared-two"),
                    names(statement, UPDATE_PREPARED_TABLE),
                    "MODULE7D prepared MVCC UPDATE WHERE id = ? must survive Derby shutdown/reopen");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE7D post-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, HEAP_TABLE),
                    "MODULE7D heap and btree compatibility must stay green after MVCC UPDATE WHERE equality restart proof");
            SmokeUtils.assertEquals(List.of("heap-one", "heap-three", "heap-two-updated"),
                    names(statement, HEAP_TABLE),
                    "MODULE7D heap updated value must survive restart");
        }
    }

    private static void assertMvccPhysicalConglomerate(Statement statement, String tableName) throws Exception {
        SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                baseConglomerateNumber(statement, tableName) & 0x0fL,
                "MODULE7D " + tableName + " must use an MVCC physical conglomerate");
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
