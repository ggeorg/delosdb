package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.FileChannelPageVolume;
import io.github.ggeorg.delosdb.storage.mvcc.MvccLogRecord;
import io.github.ggeorg.delosdb.storage.mvcc.MvccLogWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/**
 * MODULE13 smoke: Derby-lifecycle-bound minimal MVCC WAL/pageLSN.
 *
 * <p>The proof remains inherited-Derby-first: SQL enters Derby, DML reaches the
 * inherited MVCC conglomerate controller, Derby commit/rollback completes MVCC
 * writers, and MODULE11 page-volume state receives forced log records and
 * non-zero pageLSNs. This is intentionally not full ARIES and not a checkpoint
 * system.</p>
 */
public final class Module13DerbyLifecycleMvccWalPageLsnSmoke {
    private static final String DATABASE_PATH = "build/module13-derby-lifecycle-mvcc-wal-pagelsn-db";
    private static final String MVCC_TABLE = "MODULE13_WAL";
    private static final DelosStorageDiagnostics MVCC_DIAGNOSTICS = DelosStorageDiagnosticsRegistry.mvcc();

    private Module13DerbyLifecycleMvccWalPageLsnSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();
        clearNativeMvccProofProperties();

        try {
            StateFiles stateFiles = createWalBackedInheritedMvccState();
            assertWalAndPageLsn(stateFiles);
            shutdownAndClearRuntimeState();
            reopenAndAssertInheritedState(stateFiles);
            assertWalStillRecoversAfterReopen(stateFiles);
        } finally {
            clearNativeMvccProofProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static StateFiles createWalBackedInheritedMvccState() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE13 table must use inherited MVCC physical conglomerate identity");

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'two')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (3, 'rollback-live')");
            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'one-new' WHERE id = 1");
            statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 2");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1,
                        statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 3"),
                        "MODULE13 rollback DELETE must initially affect one row");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            SmokeUtils.assertEquals(List.of(1, 3), ids(statement),
                    "MODULE13 visible ids before restart must match committed MVCC state");
            SmokeUtils.assertEquals(List.of("one-new", "rollback-live"), names(statement),
                    "MODULE13 visible names before restart must match committed MVCC state");

            Path pageFile = MVCC_DIAGNOSTICS.pageVolumeStateFileForTesting(0, conglomId);
            Path rowDirectoryFile = MVCC_DIAGNOSTICS.rowDirectoryStateFileForTesting(0, conglomId);
            Path pageMutationLogFile = MVCC_DIAGNOSTICS.pageMutationLogFileForTesting(0, conglomId);
            Path walFile = MVCC_DIAGNOSTICS.writeAheadLogFileForTesting(0, conglomId);
            assertCompletePageFile(pageFile,
                    "MODULE13 inherited MVCC page-volume state must exist as complete pages before restart");
            assertNonEmptyFile(rowDirectoryFile,
                    "MODULE13 inherited MVCC row-directory sidecar must exist before restart");
            assertNonEmptyFile(pageMutationLogFile,
                    "MODULE13 inherited MVCC page mutation log must exist before restart");
            assertNonEmptyFile(walFile,
                    "MODULE13 inherited MVCC write-ahead log must exist before restart");

            require(MVCC_DIAGNOSTICS.insertCountForTesting() >= 3,
                    "MODULE13 INSERTs must reach inherited MvccConglomerateController");
            require(MVCC_DIAGNOSTICS.updateCountForTesting() >= 1,
                    "MODULE13 UPDATE must reach inherited MvccConglomerateController");
            require(MVCC_DIAGNOSTICS.deleteCountForTesting() >= 2,
                    "MODULE13 DELETEs must reach inherited MvccConglomerateController");
            require(MVCC_DIAGNOSTICS.scanOpenCountForTesting() > 0,
                    "MODULE13 SELECT must reach inherited MvccScanController before restart");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE13 must not resurrect retired native registry bridge");
            return new StateFiles(conglomId, pageFile, rowDirectoryFile, pageMutationLogFile, walFile);
        }
    }

    private static void assertWalAndPageLsn(StateFiles stateFiles) throws Exception {
        List<MvccLogRecord> records = MvccLogWriter.open(stateFiles.walFile()).recoverRecords();
        require(!records.isEmpty(), "MODULE13 inherited MVCC WAL must recover records");
        requireRecords(records, MvccLogRecord.Type.BEGIN_TXN, 1,
                "MODULE13 WAL must contain Derby-lifecycle begin records");
        requireRecords(records, MvccLogRecord.Type.INSERT_VERSION, 3,
                "MODULE13 WAL must contain insert-version records before page writes");
        requireRecords(records, MvccLogRecord.Type.UPDATE_VERSION, 1,
                "MODULE13 WAL must contain update-version records before page writes");
        requireRecords(records, MvccLogRecord.Type.DELETE_VERSION, 1,
                "MODULE13 WAL must contain delete-version records before page writes");
        requireRecords(records, MvccLogRecord.Type.COMMIT_TXN, 1,
                "MODULE13 WAL must contain commit records tied to Derby commit completion");

        long maxVersionLsn = records.stream()
                .filter(record -> record.type() == MvccLogRecord.Type.INSERT_VERSION
                        || record.type() == MvccLogRecord.Type.UPDATE_VERSION
                        || record.type() == MvccLogRecord.Type.DELETE_VERSION)
                .mapToLong(record -> record.lsn().value())
                .max()
                .orElseThrow();
        long maxCommitLsn = records.stream()
                .filter(record -> record.type() == MvccLogRecord.Type.COMMIT_TXN)
                .mapToLong(record -> record.lsn().value())
                .max()
                .orElseThrow();
        require(maxCommitLsn > maxVersionLsn,
                "MODULE13 WAL commit record must be ordered after version records");

        assertPageLsnsAreNonZeroAndLogged(stateFiles.pageFile(), maxVersionLsn);
    }

    private static void assertPageLsnsAreNonZeroAndLogged(Path pageFile, long maxLoggedVersionLsn) throws Exception {
        try (FileChannelPageVolume volume = FileChannelPageVolume.open(pageFile, DelosPageVolume.SyncPolicy.FULL)) {
            long pageCount = volume.pageCount();
            require(pageCount > 0L, "MODULE13 page-volume state must contain at least one page");
            for (long pageNumber = 0L; pageNumber < pageCount; pageNumber++) {
                DelosPage page = volume.readPage(new DelosPageId(pageNumber));
                require(page.slotCount() > 0,
                        "MODULE13 inherited MVCC page " + pageNumber + " must contain version records");
                require(page.pageLsn() > 0L,
                        "MODULE13 inherited MVCC page " + pageNumber + " must carry non-zero pageLSN");
                require(page.pageLsn() <= maxLoggedVersionLsn,
                        "MODULE13 inherited MVCC page " + pageNumber
                                + " pageLSN must not exceed the latest logged version LSN");
            }
        }
    }

    private static void shutdownAndClearRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        SmokeUtils.assertEquals(0, MVCC_DIAGNOSTICS.runtimeStateCountForTesting(),
                "MODULE13 restart proof must clear inherited MVCC runtime cache before reopen");
        resetInheritedCounters();
    }

    private static void reopenAndAssertInheritedState(StateFiles stateFiles) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            long reopenedConglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals(stateFiles.conglomId(), reopenedConglomId,
                    "MODULE13 MVCC conglomerate id must be stable across restart");
            SmokeUtils.assertEquals(List.of(1, 3), ids(statement),
                    "MODULE13 visible ids must reload from WAL/pageLSN-backed inherited MVCC state after restart");
            SmokeUtils.assertEquals(List.of("one-new", "rollback-live"), names(statement),
                    "MODULE13 visible names must reload from WAL/pageLSN-backed inherited MVCC state after restart");
            assertCompletePageFile(stateFiles.pageFile(),
                    "MODULE13 reopened inherited MVCC page-volume state must remain complete-page aligned");
            assertNonEmptyFile(stateFiles.pageMutationLogFile(),
                    "MODULE13 page mutation log must remain present after restart");
            assertNonEmptyFile(stateFiles.walFile(),
                    "MODULE13 WAL must remain present after restart");
            require(MVCC_DIAGNOSTICS.scanOpenCountForTesting() > 0,
                    "MODULE13 post-restart SELECT must reach inherited MvccScanController");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE13 reopen must not populate retired native registry bridge");
        }
    }

    private static void assertWalStillRecoversAfterReopen(StateFiles stateFiles) throws Exception {
        List<MvccLogRecord> records = MvccLogWriter.open(stateFiles.walFile()).recoverRecords();
        requireRecords(records, MvccLogRecord.Type.COMMIT_TXN, 1,
                "MODULE13 WAL must remain recoverable after inherited restart");
        long maxVersionLsn = records.stream()
                .filter(record -> record.type() == MvccLogRecord.Type.INSERT_VERSION
                        || record.type() == MvccLogRecord.Type.UPDATE_VERSION
                        || record.type() == MvccLogRecord.Type.DELETE_VERSION)
                .mapToLong(record -> record.lsn().value())
                .max()
                .orElseThrow();
        assertPageLsnsAreNonZeroAndLogged(stateFiles.pageFile(), maxVersionLsn);
    }

    private static void requireRecords(
            List<MvccLogRecord> records,
            MvccLogRecord.Type type,
            int minimumCount,
            String label) {
        Map<MvccLogRecord.Type, Integer> counts = new EnumMap<>(MvccLogRecord.Type.class);
        for (MvccLogRecord record : records) {
            counts.merge(record.type(), 1, Integer::sum);
        }
        int actual = counts.getOrDefault(type, 0);
        require(actual >= minimumCount, label + ": expected at least " + minimumCount + " but got " + actual);
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

    private record StateFiles(long conglomId, Path pageFile, Path rowDirectoryFile, Path pageMutationLogFile, Path walFile) {
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
