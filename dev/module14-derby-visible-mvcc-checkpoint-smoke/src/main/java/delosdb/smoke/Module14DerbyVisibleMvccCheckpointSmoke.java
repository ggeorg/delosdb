package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.storage.io.page.DelosPage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/**
 * MODULE14 smoke: Derby-visible MVCC checkpoint and row-directory recovery.
 *
 * <p>The proof stays inherited-Derby-first. The checkpoint binds Derby's MVCC
 * conglomerate identity to the page-volume file and row-directory sidecar. A
 * valid checkpoint must be recognized on reopen; a corrupt checkpoint must be
 * ignored with safe fallback to the page-volume/row-directory authority.</p>
 */
public final class Module14DerbyVisibleMvccCheckpointSmoke {
    private static final String DATABASE_PATH = "build/module14-derby-visible-mvcc-checkpoint-db";
    private static final String MVCC_TABLE = "MODULE14_CHECKPOINT";
    private static final DelosStorageDiagnostics MVCC_DIAGNOSTICS = DelosStorageDiagnosticsRegistry.mvcc();

    private Module14DerbyVisibleMvccCheckpointSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();
        clearNativeMvccProofProperties();

        try {
            StateFiles stateFiles = createCheckpointedInheritedMvccState();
            shutdownAndClearRuntimeState();
            reopenAndAssertValidCheckpoint(stateFiles);
            corruptCheckpoint(stateFiles.checkpointFile());
            shutdownAndClearRuntimeState();
            reopenAndAssertSafeCheckpointFallback(stateFiles);
        } finally {
            clearNativeMvccProofProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static StateFiles createCheckpointedInheritedMvccState() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE14 table must use inherited MVCC physical conglomerate identity");

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'two')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (3, 'rollback-live')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (4, 'multi-a')");
            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'one-new' WHERE id = 1");
            statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 2");
            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'multi-b' WHERE id = 4");
            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'multi-c' WHERE id = 4");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1,
                        statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 3"),
                        "MODULE14 rollback DELETE must initially affect one row");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            SmokeUtils.assertEquals(List.of(1, 3, 4), ids(statement),
                    "MODULE14 visible ids before restart must match committed MVCC state");
            SmokeUtils.assertEquals(List.of("multi-c", "one-new", "rollback-live"), names(statement),
                    "MODULE14 visible names before restart must match committed MVCC state");

            Path pageFile = MVCC_DIAGNOSTICS.pageVolumeStateFileForTesting(0, conglomId);
            Path rowDirectoryFile = MVCC_DIAGNOSTICS.rowDirectoryStateFileForTesting(0, conglomId);
            Path pageMutationLogFile = MVCC_DIAGNOSTICS.pageMutationLogFileForTesting(0, conglomId);
            Path walFile = MVCC_DIAGNOSTICS.writeAheadLogFileForTesting(0, conglomId);
            Path checkpointFile = MVCC_DIAGNOSTICS.checkpointFileForTesting(0, conglomId);

            assertCompletePageFile(pageFile,
                    "MODULE14 inherited MVCC page-volume state must exist as complete pages");
            assertNonEmptyFile(rowDirectoryFile,
                    "MODULE14 inherited MVCC row-directory sidecar must exist");
            assertNonEmptyFile(pageMutationLogFile,
                    "MODULE14 inherited MVCC page mutation log must exist");
            assertNonEmptyFile(walFile,
                    "MODULE14 inherited MVCC WAL must exist");
            assertNonEmptyFile(checkpointFile,
                    "MODULE14 Derby-visible MVCC checkpoint must exist");
            SmokeUtils.assertEquals("WRITTEN", MVCC_DIAGNOSTICS.checkpointStatusForTesting(0, conglomId),
                    "MODULE14 checkpoint status must be WRITTEN after inherited DML persists state");
            assertCheckpointMetadata(checkpointFile, conglomId, pageFile, rowDirectoryFile, pageMutationLogFile, walFile);

            require(MVCC_DIAGNOSTICS.insertCountForTesting() >= 4,
                    "MODULE14 INSERTs must reach inherited MvccConglomerateController");
            require(MVCC_DIAGNOSTICS.updateCountForTesting() >= 3,
                    "MODULE14 UPDATEs must reach inherited MvccConglomerateController");
            require(MVCC_DIAGNOSTICS.deleteCountForTesting() >= 2,
                    "MODULE14 DELETEs must reach inherited MvccConglomerateController");
            require(MVCC_DIAGNOSTICS.scanOpenCountForTesting() > 0,
                    "MODULE14 SELECT must reach inherited MvccScanController before restart");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE14 must not resurrect retired native registry bridge");
            return new StateFiles(conglomId, pageFile, rowDirectoryFile, pageMutationLogFile, walFile, checkpointFile);
        }
    }

    private static void reopenAndAssertValidCheckpoint(StateFiles stateFiles) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            long reopenedConglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals(stateFiles.conglomId(), reopenedConglomId,
                    "MODULE14 MVCC conglomerate id must be stable across checkpointed restart");
            SmokeUtils.assertEquals(List.of(1, 3, 4), ids(statement),
                    "MODULE14 visible ids must reload from checkpointed inherited MVCC state");
            SmokeUtils.assertEquals(List.of("multi-c", "one-new", "rollback-live"), names(statement),
                    "MODULE14 visible names must reload from checkpointed inherited MVCC state");
            SmokeUtils.assertEquals("VALID", MVCC_DIAGNOSTICS.checkpointStatusForTesting(0, stateFiles.conglomId()),
                    "MODULE14 valid checkpoint must be recognized after inherited restart");
            assertCheckpointMetadata(stateFiles.checkpointFile(), stateFiles.conglomId(),
                    stateFiles.pageFile(), stateFiles.rowDirectoryFile(),
                    stateFiles.pageMutationLogFile(), stateFiles.walFile());
            require(MVCC_DIAGNOSTICS.scanOpenCountForTesting() > 0,
                    "MODULE14 post-restart SELECT must reach inherited MvccScanController");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE14 checkpoint reopen must not populate retired native registry bridge");
        }
    }

    private static void reopenAndAssertSafeCheckpointFallback(StateFiles stateFiles) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals(List.of(1, 3, 4), ids(statement),
                    "MODULE14 corrupt checkpoint fallback must still recover visible ids from page-volume state");
            SmokeUtils.assertEquals(List.of("multi-c", "one-new", "rollback-live"), names(statement),
                    "MODULE14 corrupt checkpoint fallback must still recover visible names from page-volume state");
            SmokeUtils.assertEquals("FALLBACK", MVCC_DIAGNOSTICS.checkpointStatusForTesting(0, stateFiles.conglomId()),
                    "MODULE14 corrupt checkpoint must be ignored with safe fallback");
            assertCompletePageFile(stateFiles.pageFile(),
                    "MODULE14 fallback must leave page-volume state complete-page aligned");
            assertNonEmptyFile(stateFiles.rowDirectoryFile(),
                    "MODULE14 fallback must keep row-directory sidecar available");
            require(MVCC_DIAGNOSTICS.scanOpenCountForTesting() > 0,
                    "MODULE14 fallback SELECT must reach inherited MvccScanController");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE14 fallback must not populate retired native registry bridge");
        }
    }

    private static void corruptCheckpoint(Path checkpointFile) throws Exception {
        assertNonEmptyFile(checkpointFile, "MODULE14 checkpoint must exist before corruption test");
        Files.writeString(checkpointFile, "poison=true\n", StandardCharsets.UTF_8);
    }

    private static void assertCheckpointMetadata(
            Path checkpointFile,
            long conglomId,
            Path pageFile,
            Path rowDirectoryFile,
            Path pageMutationLogFile,
            Path walFile) throws Exception {
        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(checkpointFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        SmokeUtils.assertEquals("DELOS_INHERITED_MVCC_CHECKPOINT", properties.getProperty("magic"),
                "MODULE14 checkpoint must carry expected magic");
        SmokeUtils.assertEquals("1", properties.getProperty("version"),
                "MODULE14 checkpoint must carry expected format version");
        SmokeUtils.assertEquals("0", properties.getProperty("segment"),
                "MODULE14 checkpoint must bind Derby segment id");
        SmokeUtils.assertEquals(Long.toString(conglomId), properties.getProperty("container"),
                "MODULE14 checkpoint must bind Derby MVCC conglomerate id");
        SmokeUtils.assertEquals(pageFile.getFileName().toString(), properties.getProperty("pageFile"),
                "MODULE14 checkpoint must point at inherited page-volume file");
        SmokeUtils.assertEquals(rowDirectoryFile.getFileName().toString(), properties.getProperty("rowDirectoryFile"),
                "MODULE14 checkpoint must point at inherited row-directory sidecar");
        SmokeUtils.assertEquals(pageMutationLogFile.getFileName().toString(), properties.getProperty("pageMutationLogFile"),
                "MODULE14 checkpoint must point at inherited page mutation log");
        SmokeUtils.assertEquals(walFile.getFileName().toString(), properties.getProperty("writeAheadLogFile"),
                "MODULE14 checkpoint must point at inherited MVCC WAL");
        SmokeUtils.assertEquals("4", properties.getProperty("headCount"),
                "MODULE14 checkpoint must include live and tombstone row-directory heads");
        require(properties.getProperty("rowHeadDigest") != null && properties.getProperty("rowHeadDigest").length() == 64,
                "MODULE14 checkpoint must carry row-directory head digest");
    }

    private static void shutdownAndClearRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        SmokeUtils.assertEquals(0, MVCC_DIAGNOSTICS.runtimeStateCountForTesting(),
                "MODULE14 restart proof must clear inherited MVCC runtime cache before reopen");
        resetInheritedCounters();
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

    private static void assertCompletePageFile(Path path, String label) throws Exception {
        assertNonEmptyFile(path, label);
        long size = Files.size(path);
        require(size % DelosPage.PAGE_SIZE == 0L,
                label + ": size " + size + " is not a multiple of Delos page size " + DelosPage.PAGE_SIZE);
    }

    private static void assertNonEmptyFile(Path path, String label) throws Exception {
        require(path != null, label + " path must not be null");
        require(Files.exists(path), label + ": missing " + path);
        require(Files.size(path) > 0L, label + ": empty " + path);
    }

    private static void clearRuntimeState() {
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MVCC_DIAGNOSTICS.clearRuntimeStateForTesting();
    }

    private static void resetInheritedCounters() {
        MVCC_DIAGNOSTICS.resetMutationCountersForTesting();
        MVCC_DIAGNOSTICS.resetScanCountersForTesting();
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
            Path pageMutationLogFile,
            Path walFile,
            Path checkpointFile) {
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
