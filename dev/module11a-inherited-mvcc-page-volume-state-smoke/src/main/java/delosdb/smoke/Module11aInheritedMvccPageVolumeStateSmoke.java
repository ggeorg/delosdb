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
import org.apache.derby.impl.store.access.mvcc.MvccConglomerate;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE11A smoke: inherited MVCC conglomerate page-volume state backing.
 *
 * <p>This is the first storage-engine-boundary proof after the MODULE10 bridge
 * retirement lane. It keeps inherited Derby integration as the gate: SQL enters
 * Derby normally, DML reaches {@code MvccConglomerateController}, SELECT reaches
 * {@code MvccScanController}, and committed state reloads after shutdown/cache
 * clear from Delos page-volume backed files rather than the MODULE9A ad-hoc
 * snapshot file.</p>
 */
public final class Module11aInheritedMvccPageVolumeStateSmoke {
    private static final String DATABASE_PATH = "build/module11a-inherited-mvcc-page-volume-state-db";
    private static final String MVCC_TABLE = "MODULE11A_STATE";

    private Module11aInheritedMvccPageVolumeStateSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();
        clearNativeMvccProofProperties();

        try {
            long conglomId = createMutateAndAssertPageVolumeBacking();
            shutdownAndClearRuntimeState();
            reopenAndAssertPageVolumeReload(conglomId);
        } finally {
            clearNativeMvccProofProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static long createMutateAndAssertPageVolumeBacking() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE11A table must use inherited MVCC physical conglomerate identity");

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'two')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (3, 'rollback-live')");
            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'one-new' WHERE id = 1");
            statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 2");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1,
                        statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 3"),
                        "MODULE11A rollback DELETE must initially affect one row");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            SmokeUtils.assertEquals(List.of(1, 3), ids(statement),
                    "MODULE11A visible ids before restart must match committed MVCC state");
            SmokeUtils.assertEquals(List.of("one-new", "rollback-live"), names(statement),
                    "MODULE11A visible values before restart must match committed MVCC state");

            Path pageFile = MvccConglomerate.pageVolumeStateFileForTesting(0, conglomId);
            Path rowDirectoryFile = MvccConglomerate.rowDirectoryStateFileForTesting(0, conglomId);
            Path legacySnapshotFile = MvccConglomerate.legacySnapshotFileForTesting(0, conglomId);
            assertNonEmptyFile(pageFile, "MODULE11A must persist inherited MVCC state to a page-volume file");
            assertNonEmptyFile(rowDirectoryFile,
                    "MODULE11A must persist inherited MVCC row-directory heads beside the page-volume file");
            require(!Files.exists(legacySnapshotFile),
                    "MODULE11A must not write the MODULE9A ad-hoc snapshot as the new state authority: "
                            + legacySnapshotFile);

            require(MvccConglomerateController.insertCountForTesting() >= 3,
                    "MODULE11A INSERTs must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.updateCountForTesting() >= 1,
                    "MODULE11A UPDATE must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.deleteCountForTesting() >= 2,
                    "MODULE11A DELETEs must reach inherited MvccConglomerateController");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE11A SELECT must reach inherited MvccScanController before restart");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE11A must not resurrect the retired native table registry bridge");

            return conglomId;
        }
    }

    private static void shutdownAndClearRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        SmokeUtils.assertEquals(0, MvccConglomerate.stateCountForTesting(),
                "MODULE11A restart proof must clear inherited MVCC runtime cache before reopen");
        resetInheritedCounters();
    }

    private static void reopenAndAssertPageVolumeReload(long conglomId) throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            long reopenedConglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals(conglomId, reopenedConglomId,
                    "MODULE11A MVCC conglomerate id must be stable across restart");
            SmokeUtils.assertEquals(List.of(1, 3), ids(statement),
                    "MODULE11A page-volume backed visible ids must reload after runtime cache clear");
            SmokeUtils.assertEquals(List.of("one-new", "rollback-live"), names(statement),
                    "MODULE11A page-volume backed visible values must reload after runtime cache clear");

            Path pageFile = MvccConglomerate.pageVolumeStateFileForTesting(0, reopenedConglomId);
            Path rowDirectoryFile = MvccConglomerate.rowDirectoryStateFileForTesting(0, reopenedConglomId);
            Path legacySnapshotFile = MvccConglomerate.legacySnapshotFileForTesting(0, reopenedConglomId);
            assertNonEmptyFile(pageFile,
                    "MODULE11A reopened inherited MVCC provider must still have page-volume state");
            assertNonEmptyFile(rowDirectoryFile,
                    "MODULE11A reopened inherited MVCC provider must still have row-directory state");
            require(!Files.exists(legacySnapshotFile),
                    "MODULE11A reopen must not fall back to the MODULE9A ad-hoc snapshot authority");

            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE11A post-restart SELECT must reach inherited MvccScanController");
            require(MvccConglomerate.stateCountForTesting() > 0,
                    "MODULE11A post-restart SELECT must reload inherited MVCC state");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE11A reopen must not populate the retired native table registry bridge");
        }
    }

    private static List<Integer> ids(Statement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT id FROM APP." + MVCC_TABLE)) {
            List<Integer> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getInt(1));
            }
            values.sort(Integer::compareTo);
            return List.copyOf(values);
        }
    }

    private static List<String> names(Statement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT name FROM APP." + MVCC_TABLE)) {
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

    private static void assertNonEmptyFile(Path path, String label) throws Exception {
        require(path != null, label + " path must not be null");
        require(Files.exists(path), label + ": missing " + path);
        require(Files.size(path) > 0L, label + ": empty " + path);
    }

    private static void clearRuntimeState() {
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerate.clearStatesForTesting();
        MvccStoreAccessTransactionRegistry.clearForTesting();
    }

    private static void resetInheritedCounters() {
        MvccConglomerateController.resetInsertCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();
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
