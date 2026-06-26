package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.charset.StandardCharsets;
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
 * MODULE11C smoke: retire MODULE9A snapshot state authority.
 *
 * <p>MODULE11A/11B moved inherited MVCC committed-row and row-directory reload
 * to Delos page-volume files. MODULE11C removes the old MODULE9A snapshot
 * fallback from production code. This smoke writes a poison file at the old
 * {@code .snapshot} location and proves restart still reloads from the inherited
 * page-volume state through normal Derby SQL/store/access paths.</p>
 */
public final class Module11cRetireSnapshotStateAuthoritySmoke {
    private static final String DATABASE_PATH = "build/module11c-retire-snapshot-state-authority-db";
    private static final String MVCC_TABLE = "MODULE11C_STATE";

    private Module11cRetireSnapshotStateAuthoritySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();
        clearNativeMvccProofProperties();

        try {
            StateFiles stateFiles = createStateAndWritePoisonRetiredSnapshot();
            shutdownAndClearRuntimeState();
            reopenAndAssertPageVolumeStillAuthoritative(stateFiles);
        } finally {
            clearNativeMvccProofProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static StateFiles createStateAndWritePoisonRetiredSnapshot() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE11C table must use inherited MVCC physical conglomerate identity");

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'delete-me')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (3, 'rollback-live')");
            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'one-new' WHERE id = 1");
            statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 2");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1,
                        statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 3"),
                        "MODULE11C rollback DELETE must initially affect one row");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            SmokeUtils.assertEquals(List.of(1, 3), ids(statement),
                    "MODULE11C visible ids before restart must match committed MVCC state");
            SmokeUtils.assertEquals(List.of("one-new", "rollback-live"), names(statement),
                    "MODULE11C visible values before restart must match committed MVCC state");

            Path pageFile = MvccConglomerate.pageVolumeStateFileForTesting(0, conglomId);
            Path rowDirectoryFile = MvccConglomerate.rowDirectoryStateFileForTesting(0, conglomId);
            Path retiredSnapshotFile = MvccConglomerate.legacySnapshotFileForTesting(0, conglomId);
            assertNonEmptyFile(pageFile, "MODULE11C page-volume state must exist before poison snapshot");
            assertNonEmptyFile(rowDirectoryFile,
                    "MODULE11C row-directory state must exist before poison snapshot");
            require(retiredSnapshotFile != null,
                    "MODULE11C retired MODULE9A snapshot path must be available for inertness proof");
            require(!Files.exists(retiredSnapshotFile),
                    "MODULE11C old MODULE9A snapshot file must not be written by normal persistence");

            Files.createDirectories(retiredSnapshotFile.getParent());
            Files.writeString(retiredSnapshotFile,
                    "MODULE11C poison: retired snapshot fallback must not be read\n",
                    StandardCharsets.UTF_8);
            assertNonEmptyFile(retiredSnapshotFile,
                    "MODULE11C poison retired snapshot file must be present before restart");

            require(MvccConglomerateController.insertCountForTesting() >= 3,
                    "MODULE11C INSERTs must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.updateCountForTesting() >= 1,
                    "MODULE11C UPDATE must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.deleteCountForTesting() >= 2,
                    "MODULE11C DELETEs must reach inherited MvccConglomerateController");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE11C SELECT must reach inherited MvccScanController before restart");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE11C must not resurrect the retired native table registry bridge");

            return new StateFiles(conglomId, pageFile, rowDirectoryFile, retiredSnapshotFile);
        }
    }

    private static void shutdownAndClearRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        SmokeUtils.assertEquals(0, MvccConglomerate.stateCountForTesting(),
                "MODULE11C restart proof must clear inherited MVCC runtime cache before reopen");
        resetInheritedCounters();
    }

    private static void reopenAndAssertPageVolumeStillAuthoritative(StateFiles stateFiles) throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            long reopenedConglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals(stateFiles.conglomId(), reopenedConglomId,
                    "MODULE11C MVCC conglomerate id must be stable across restart");
            SmokeUtils.assertEquals(List.of(1, 3), ids(statement),
                    "MODULE11C must reload visible ids from page-volume state, not retired snapshot");
            SmokeUtils.assertEquals(List.of("one-new", "rollback-live"), names(statement),
                    "MODULE11C must reload visible values from page-volume state, not retired snapshot");

            assertNonEmptyFile(stateFiles.pageFile(),
                    "MODULE11C reopened inherited MVCC provider must still have page-volume state");
            assertNonEmptyFile(stateFiles.rowDirectoryFile(),
                    "MODULE11C reopened inherited MVCC provider must still have row-directory state");
            assertNonEmptyFile(stateFiles.retiredSnapshotFile(),
                    "MODULE11C poison retired snapshot file should remain inert across reopen");

            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE11C post-restart SELECT must reach inherited MvccScanController");
            require(MvccConglomerate.stateCountForTesting() > 0,
                    "MODULE11C post-restart SELECT must reload inherited MVCC state");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE11C reopen must not populate the retired native table registry bridge");
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

    private record StateFiles(
            long conglomId,
            Path pageFile,
            Path rowDirectoryFile,
            Path retiredSnapshotFile) {
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
