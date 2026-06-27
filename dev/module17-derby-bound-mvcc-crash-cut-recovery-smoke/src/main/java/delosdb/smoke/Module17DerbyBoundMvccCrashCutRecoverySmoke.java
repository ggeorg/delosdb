package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccLogRecord;
import io.github.ggeorg.delosdb.storage.mvcc.MvccLogWriter;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccPageMutationLog;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowPayload;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowPayloadCodec;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerate;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE17 smoke: Derby-bound MVCC crash-cut recovery.
 *
 * <p>This proof deliberately stays on the inherited Derby SQL/store/access path.
 * It simulates the important crash cuts after MODULE13/14 by leaving the WAL and
 * page-mutation log intact while restoring stale page-volume / row-directory /
 * checkpoint files. Reopen must recover committed changes from the page mutation
 * log, and it must ignore incomplete or uncommitted log tails.</p>
 */
public final class Module17DerbyBoundMvccCrashCutRecoverySmoke {
    private static final String DATABASE_PATH = "build/module17-derby-bound-mvcc-crash-cut-recovery-db";
    private static final String MVCC_TABLE = "MODULE17_CRASH_CUT";

    private Module17DerbyBoundMvccCrashCutRecoverySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();

        try {
            CrashState state = createInitialCommittedImage();
            Path stalePageBackup = backup(state.pageFile(), ".before-crash-cut");
            Path staleRowDirectoryBackup = backup(state.rowDirectoryFile(), ".before-crash-cut");
            Path staleCheckpointBackup = backup(state.checkpointFile(), ".before-crash-cut");

            applyCommittedChangesAfterBackup(state.conglomId());
            appendUncommittedAndTornPageMutationLogTail(state.pageMutationLogFile());
            restoreStaleStorageImage(state, stalePageBackup, staleRowDirectoryBackup, staleCheckpointBackup);

            reopenAndProveCrashCutRecovery(state.conglomId());
        } finally {
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static CrashState createInitialCommittedImage() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE17 table must use inherited MVCC physical conglomerate identity");

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'delete-me')");
            SmokeUtils.assertEquals(List.of(1, 2), ids(statement),
                    "MODULE17 initial visible ids must be committed before crash-cut backup");
            SmokeUtils.assertEquals(List.of("alpha", "delete-me"), names(statement),
                    "MODULE17 initial visible names must be committed before crash-cut backup");

            Path pageFile = MvccConglomerate.pageVolumeStateFileForTesting(0, conglomId);
            Path rowDirectoryFile = MvccConglomerate.rowDirectoryStateFileForTesting(0, conglomId);
            Path pageMutationLogFile = MvccConglomerate.pageMutationLogFileForTesting(0, conglomId);
            Path writeAheadLogFile = MvccConglomerate.writeAheadLogFileForTesting(0, conglomId);
            Path checkpointFile = MvccConglomerate.checkpointFileForTesting(0, conglomId);
            requireExistingNonEmpty(pageFile, "MODULE17 initial page-volume state");
            requireExistingNonEmpty(rowDirectoryFile, "MODULE17 initial row-directory sidecar");
            requireExistingNonEmpty(pageMutationLogFile, "MODULE17 initial page mutation log");
            requireExistingNonEmpty(writeAheadLogFile, "MODULE17 initial inherited WAL");
            requireExistingNonEmpty(checkpointFile, "MODULE17 initial checkpoint metadata");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE17 initial reads must reach inherited MvccScanController");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE17 must not resurrect retired native registry bridge");
            return new CrashState(conglomId, pageFile, rowDirectoryFile, pageMutationLogFile,
                    writeAheadLogFile, checkpointFile);
        } finally {
            shutdownAndClearRuntimeState();
        }
    }

    private static void applyCommittedChangesAfterBackup(long conglomId) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'beta' WHERE id = 1");
            statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 2");
            SmokeUtils.assertEquals(List.of(1), ids(statement),
                    "MODULE17 committed update/delete must be visible before simulated crash");
            SmokeUtils.assertEquals(List.of("beta"), names(statement),
                    "MODULE17 committed update must be visible before simulated crash");
            require(MvccConglomerate.physicalVersionCountForTesting(0, conglomId) >= 4,
                    "MODULE17 committed update/delete must append durable MVCC versions");
            requireWalContainsCommittedUpdateAndDelete(
                    MvccConglomerate.writeAheadLogFileForTesting(0, conglomId));
        } finally {
            shutdownAndClearRuntimeState();
        }
    }

    private static void appendUncommittedAndTornPageMutationLogTail(Path pageMutationLogFile) throws Exception {
        MvccPageMutationLog log = MvccPageMutationLog.open(pageMutationLogFile);
        MvccVersionRecord phantom = new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(99L),
                        new MvccVersionId(999L),
                        MvccVersionId.NONE,
                        new MvccTransactionId(999L),
                        MvccTransactionId.NONE,
                        MvccCommitSequence.NONE,
                        0),
                MvccRowPayloadCodec.encode(new MvccRowPayload("row:99", "phantom".getBytes(StandardCharsets.UTF_8))));
        log.appendVersion(999L, phantom);

        // Simulate a crash while appending the next page-mutation record. Recovery
        // should ignore this incomplete final line and still replay earlier
        // committed records.
        Files.writeString(pageMutationLogFile, "1\tVERSION\t1000", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static void restoreStaleStorageImage(
            CrashState state,
            Path stalePageBackup,
            Path staleRowDirectoryBackup,
            Path staleCheckpointBackup) throws Exception {
        Files.copy(stalePageBackup, state.pageFile(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(staleRowDirectoryBackup, state.rowDirectoryFile(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(staleCheckpointBackup, state.checkpointFile(), StandardCopyOption.REPLACE_EXISTING);
        requireExistingNonEmpty(state.pageMutationLogFile(),
                "MODULE17 page mutation log must remain intact for recovery replay");
        requireExistingNonEmpty(state.writeAheadLogFile(),
                "MODULE17 inherited WAL must remain intact across simulated crash");
    }

    private static void reopenAndProveCrashCutRecovery(long conglomId) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals(List.of(1), ids(statement),
                    "MODULE17 committed delete must survive stale-page crash cut");
            SmokeUtils.assertEquals(List.of("beta"), names(statement),
                    "MODULE17 committed update must replay from page mutation log after stale-page crash cut");
            SmokeUtils.assertEquals(0, count(statement, "SELECT COUNT(*) FROM APP." + MVCC_TABLE + " WHERE id = 99"),
                    "MODULE17 uncommitted page-mutation tail must not become visible after recovery");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE17 recovery read must enter inherited MvccScanController");
            require(MvccConglomerate.physicalVersionCountForTesting(0, conglomId) >= 4,
                    "MODULE17 recovery must materialize committed page-mutation versions");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE17 recovery must not use retired native registry bridge");
        }
    }

    private static void requireWalContainsCommittedUpdateAndDelete(Path writeAheadLogFile) {
        List<MvccLogRecord> records = MvccLogWriter.open(writeAheadLogFile).recoverRecords();
        boolean sawUpdate = false;
        boolean sawDelete = false;
        boolean sawCommitAfterUpdateOrDelete = false;
        for (MvccLogRecord record : records) {
            if (record.type() == MvccLogRecord.Type.UPDATE_VERSION) {
                sawUpdate = true;
            } else if (record.type() == MvccLogRecord.Type.DELETE_VERSION) {
                sawDelete = true;
            } else if (record.type() == MvccLogRecord.Type.COMMIT_TXN && (sawUpdate || sawDelete)) {
                sawCommitAfterUpdateOrDelete = true;
            }
        }
        require(sawUpdate, "MODULE17 inherited WAL must contain UPDATE_VERSION before crash-cut replay");
        require(sawDelete, "MODULE17 inherited WAL must contain DELETE_VERSION before crash-cut replay");
        require(sawCommitAfterUpdateOrDelete,
                "MODULE17 inherited WAL must contain COMMIT_TXN after committed version records");
    }

    private static long baseConglomerateNumber(Statement statement, String tableName) throws Exception {
        try (ResultSet results = statement.executeQuery(
                "SELECT c.conglomeratenumber "
                        + "FROM sys.systables t, sys.sysconglomerates c "
                        + "WHERE t.tableid = c.tableid "
                        + "AND t.tablename = '" + tableName + "' "
                        + "AND c.isindex = false")) {
            if (!results.next()) {
                throw new AssertionError("Could not find base conglomerate for " + tableName);
            }
            long conglomId = results.getLong(1);
            if (results.next()) {
                throw new AssertionError("Expected one base conglomerate for " + tableName);
            }
            return conglomId;
        }
    }

    private static List<Integer> ids(Statement statement) throws Exception {
        List<Integer> values = new ArrayList<>();
        try (ResultSet results = statement.executeQuery("SELECT id FROM APP." + MVCC_TABLE + " ORDER BY id")) {
            while (results.next()) {
                values.add(results.getInt(1));
            }
        }
        return values;
    }

    private static List<String> names(Statement statement) throws Exception {
        List<String> values = new ArrayList<>();
        try (ResultSet results = statement.executeQuery("SELECT name FROM APP." + MVCC_TABLE + " ORDER BY id")) {
            while (results.next()) {
                values.add(results.getString(1));
            }
        }
        return values;
    }

    private static int count(Statement statement, String sql) throws Exception {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new AssertionError("No count returned for " + sql);
            }
            int value = results.getInt(1);
            if (results.next()) {
                throw new AssertionError("More than one count row returned for " + sql);
            }
            return value;
        }
    }

    private static Path backup(Path source, String suffix) throws Exception {
        Path backup = source.resolveSibling(source.getFileName() + suffix);
        Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private static void requireExistingNonEmpty(Path path, String label) throws Exception {
        require(path != null, label + " path must be available");
        require(Files.exists(path), label + " file must exist: " + path);
        require(Files.size(path) > 0L, label + " file must be non-empty: " + path);
    }

    private static void resetInheritedCounters() {
        MvccScanController.resetOpenCountForTesting();
        MvccScanController.resetQualifierRejectCountForTesting();
        MvccScanController.resetCandidateIndexCountsForTesting();
    }

    private static void shutdownAndClearRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
    }

    private static void clearRuntimeState() {
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccStoreAccessTransactionRegistry.clearForTesting();
        MvccConglomerate.clearStatesForTesting();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record CrashState(
            long conglomId,
            Path pageFile,
            Path rowDirectoryFile,
            Path pageMutationLogFile,
            Path writeAheadLogFile,
            Path checkpointFile) {
    }
}
