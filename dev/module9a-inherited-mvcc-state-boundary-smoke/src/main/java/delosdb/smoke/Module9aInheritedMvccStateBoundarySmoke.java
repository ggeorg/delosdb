package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerate;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE9A smoke: inherited MVCC state ownership boundary.
 *
 * <p>This is the first anti-fake-restart proof. It creates and mutates a
 * {@code USING delos_mvcc} table through normal inherited SQL/store access,
 * shuts Derby down, clears the inherited MVCC static state cache, reopens the
 * database, and proves committed visible state is reloaded through the inherited
 * MVCC conglomerate provider. It deliberately does not add WAL, checkpoints,
 * indexes, native I/O, or bridge persistence.</p>
 */
public final class Module9aInheritedMvccStateBoundarySmoke {
    private static final String DATABASE_PATH = "build/module9a-inherited-mvcc-state-boundary-db";
    private static final String MVCC_TABLE = "MODULE9A_STATE";
    private static final String HEAP_TABLE = "MODULE9A_HEAP";

    private Module9aInheritedMvccStateBoundarySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerate.clearStatesForTesting();
        clearNativeMvccProofProperties();

        try {
            createAndMutateThroughInheritedSql();
            forceRealShutdownAndClearInheritedMvccStateCache();
            reopenAndAssertStateReloadsThroughInheritedProvider();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            MvccConglomerate.clearStatesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void createAndMutateThroughInheritedSql() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetInsertCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (9, 'heap-live')");
            SmokeUtils.assertEquals(List.of(9), ids(statement, HEAP_TABLE),
                    "MODULE9A heap table must stay on inherited Derby heap path before restart");

            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, MVCC_TABLE) & 0x0fL,
                    "MODULE9A table must have an MVCC physical conglomerate");

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'old')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'delete-me')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (3, 'rollback-live')");

            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'new' WHERE id = 1");
            statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 2");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1,
                        statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 3"),
                        "MODULE9A rollback DELETE must affect one MVCC row before rollback");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            SmokeUtils.assertEquals(List.of(1, 3), ids(statement, MVCC_TABLE),
                    "MODULE9A committed visible MVCC ids must be correct before restart");
            SmokeUtils.assertEquals(List.of("new", "rollback-live"), names(statement, MVCC_TABLE),
                    "MODULE9A committed visible MVCC values must be correct before restart");

            require(MvccConglomerateController.insertCountForTesting() >= 3,
                    "MODULE9A INSERTs must reach MvccConglomerateController through inherited SQL");
            require(MvccConglomerateController.updateCountForTesting() >= 1,
                    "MODULE9A UPDATE must reach MvccConglomerateController through inherited SQL");
            require(MvccConglomerateController.deleteCountForTesting() >= 2,
                    "MODULE9A DELETEs must reach MvccConglomerateController through inherited SQL");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE9A pre-restart SELECT must reach MvccScanController");
            require(MvccConglomerate.stateCountForTesting() > 0,
                    "MODULE9A setup must populate the inherited MVCC state cache before clearing it");
        }
    }

    private static void forceRealShutdownAndClearInheritedMvccStateCache() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerate.clearStatesForTesting();
        SmokeUtils.assertEquals(0,
                MvccConglomerate.stateCountForTesting(),
                "MODULE9A must clear the inherited MVCC static state cache before reopen");
        MvccConglomerateController.resetInsertCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void reopenAndAssertStateReloadsThroughInheritedProvider() throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, MVCC_TABLE) & 0x0fL,
                    "MODULE9A MVCC table identity must survive restart");

            SmokeUtils.assertEquals(List.of(1, 3), ids(statement, MVCC_TABLE),
                    "MODULE9A committed visible MVCC ids must reload after static cache clear");
            SmokeUtils.assertEquals(List.of("new", "rollback-live"), names(statement, MVCC_TABLE),
                    "MODULE9A committed visible MVCC values must reload after static cache clear");
            SmokeUtils.assertEquals(List.of(9), ids(statement, HEAP_TABLE),
                    "MODULE9A heap table must remain unaffected by inherited MVCC state boundary");

            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE9A post-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            require(MvccConglomerate.stateCountForTesting() > 0,
                    "MODULE9A reopen must repopulate inherited MVCC state through provider boundary");
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
