package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.store.MvccSubsystemRecoveryRecordStore;

/** Adversarial Phase L proofs for strict MVCC recovery replay. */
final class MvccRecoveryReplayEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void duplicateReplayIsIdempotentWithinOneLogAndAcrossRepeatedBoots() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");
        MvccVersionRecord record = version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0);

        MvccPageMutationLog mutationLog = MvccPageMutationLog.open(mutationLogFile);
        mutationLog.appendVersion(1L, record);
        mutationLog.appendVersion(1L, record);
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendCommit(1L, 1L);

        MvccRecoveryReplayEngine.ReplayResult first =
                MvccRecoveryReplayEngine.recoverStrict(mutationLogFile, outcomeLogFile, tableFile);
        assertEquals(1, first.appliedRecords());
        assertEquals(1, first.skippedExistingRecords());

        MvccRecoveryReplayEngine.ReplayResult second =
                MvccRecoveryReplayEngine.recoverStrict(mutationLogFile, outcomeLogFile, tableFile);
        assertEquals(0, second.appliedRecords());
        assertEquals(2, second.skippedExistingRecords());

        try (PageBackedMvccTable table = PageBackedMvccTable.openStrict(
                tableFile, mutationLogFile, outcomeLogFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertEquals(1, table.physicalVersionCount());
            assertTrue(table.validateConsistency().valid());
        }
    }

    @Test
    void arbitraryWalOffsetTruncationIgnoresOnlyTornFinalRecords() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");

        MvccPageMutationLog.open(mutationLogFile)
                .appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        MvccPageMutationLog.open(mutationLogFile).appendCommit(1L, 1L);
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendCommit(1L, 1L);

        appendWithoutNewline(mutationLogFile, "1\tVERSION\t2\tthis-is-a-torn-record");
        appendWithoutNewline(outcomeLogFile, "1\tCOMMIT\t2");

        MvccRecoveryReplayEngine.ReplayResult result =
                MvccRecoveryReplayEngine.recoverStrict(mutationLogFile, outcomeLogFile, tableFile);
        assertEquals(1, result.appliedRecords());
        assertEquals(0, result.skippedExistingRecords());

        try (PageBackedMvccTable table = PageBackedMvccTable.openStrict(
                tableFile, mutationLogFile, outcomeLogFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertEquals(1, table.physicalVersionCount());
            assertTrue(table.validateConsistency().valid());
        }
    }

    @Test
    void completeCorruptMutationRecordFailsLoudlyInsteadOfBeingIgnored() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");

        MvccPageMutationLog.open(mutationLogFile)
                .appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendCommit(1L, 1L);
        Files.writeString(mutationLogFile, "1\tVERSION\t2\tbad-record\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> MvccRecoveryReplayEngine.recoverStrict(mutationLogFile, outcomeLogFile, tableFile));
        assertTrue(failure.getMessage().contains("Corrupt MVCC page mutation log"));
    }

    @Test
    void tornRewriteSiblingIsIgnoredDuringReplayAndReopen() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");

        MvccPageMutationLog.open(mutationLogFile)
                .appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendCommit(1L, 1L);
        Files.writeString(tableFile.resolveSibling(tableFile.getFileName() + ".rewrite"),
                "torn rewrite candidate", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        MvccRecoveryReplayEngine.ReplayResult result =
                MvccRecoveryReplayEngine.recoverStrict(mutationLogFile, outcomeLogFile, tableFile);
        assertEquals(1, result.appliedRecords());

        try (PageBackedMvccTable table = PageBackedMvccTable.openStrict(
                tableFile, mutationLogFile, outcomeLogFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertTrue(table.validateConsistency().valid());
        }
    }

    @Test
    void crossSubsystemCompletenessRejectsCrashBetweenRedoApplications() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");
        MvccPageMutationLog.open(mutationLogFile)
                .appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendCommit(1L, 1L);

        MvccSubsystemRecoveryRecordStore subsystemRecords = MvccSubsystemRecoveryRecordStore.open(
                tempDir, "conglomerate-0-7");
        subsystemRecords.appendRowPageRedo(1L, 1L, 1L, 1L);
        subsystemRecords.appendTransactionOutcomeRedo(1L, 1L);

        IllegalStateException incomplete = assertThrows(IllegalStateException.class,
                () -> MvccRecoveryReplayEngine.recoverStrict(
                        mutationLogFile,
                        outcomeLogFile,
                        tableFile,
                        subsystemRecords,
                        MvccRecoveryReplayEngine.rowIndexOverflowFreeSpaceOutcomeSubsystems()));
        assertTrue(incomplete.getMessage().contains("missing required subsystem redo"));

        subsystemRecords.appendIndexPageRedo(1L, 1L);
        subsystemRecords.appendOverflowPageRedo(1L, 16L);
        subsystemRecords.appendFreeSpaceMapRedo(1L, 1L);
        MvccRecoveryReplayEngine.ReplayResult result = MvccRecoveryReplayEngine.recoverStrict(
                mutationLogFile,
                outcomeLogFile,
                tableFile,
                subsystemRecords,
                MvccRecoveryReplayEngine.rowIndexOverflowFreeSpaceOutcomeSubsystems());
        assertEquals(1, result.appliedRecords());
        assertEquals(5, result.requiredSubsystems());
        assertEquals(5, result.subsystemRecords());
    }


    @Test
    void pageBackedOpenStrictEnforcesSubsystemRecoveryPlanOnProductionOpen() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");
        MvccPageMutationLog.open(mutationLogFile)
                .appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendCommit(1L, 1L);

        MvccSubsystemRecoveryRecordStore subsystemRecords = MvccSubsystemRecoveryRecordStore.open(
                tempDir, "conglomerate-0-10");
        subsystemRecords.appendRowPageRedo(1L, 1L, 1L, 1L);
        subsystemRecords.appendTransactionOutcomeRedo(1L, 1L);

        IllegalStateException incomplete = assertThrows(IllegalStateException.class,
                () -> PageBackedMvccTable.openStrict(
                        tableFile,
                        mutationLogFile,
                        outcomeLogFile,
                        subsystemRecords.replayPlan()));
        assertTrue(incomplete.getMessage().contains("missing required subsystem redo"));

        subsystemRecords.appendIndexPageRedo(1L, 1L);
        subsystemRecords.appendOverflowPageRedo(1L, 16L);
        subsystemRecords.appendFreeSpaceMapRedo(1L, 1L);
        try (PageBackedMvccTable table = PageBackedMvccTable.openStrict(
                tableFile,
                mutationLogFile,
                outcomeLogFile,
                subsystemRecords.replayPlan())) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertTrue(table.validateConsistency().valid());
        }
    }

    @Test
    void rowPageRedoWithoutMatchingTransactionOutcomeIsRejectedBeforeReplay() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");
        MvccPageMutationLog.open(mutationLogFile)
                .appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendCommit(1L, 1L);

        MvccSubsystemRecoveryRecordStore subsystemRecords = MvccSubsystemRecoveryRecordStore.open(
                tempDir, "conglomerate-0-8");
        subsystemRecords.appendRowPageRedo(1L, 1L, 1L, 1L);
        subsystemRecords.appendTransactionOutcomeRedo(1L, 2L);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> MvccRecoveryReplayEngine.recoverStrict(
                        mutationLogFile,
                        outcomeLogFile,
                        tableFile,
                        subsystemRecords,
                        java.util.Set.of(MvccSubsystemRecoveryRecordStore.Subsystem.ROW_PAGE,
                                MvccSubsystemRecoveryRecordStore.Subsystem.TRANSACTION_OUTCOME)));
        assertTrue(failure.getMessage().contains("no matching transaction-outcome redo"));
    }

    private static void appendWithoutNewline(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static MvccVersionRecord version(
            String key,
            String value,
            long rowId,
            long versionId,
            long previousVersionId,
            long createdByTx,
            long deletedByTx,
            int flags) {
        return new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(rowId),
                        new MvccVersionId(versionId),
                        previousVersionId == 0L ? MvccVersionId.NONE : new MvccVersionId(previousVersionId),
                        new MvccTransactionId(createdByTx),
                        new MvccTransactionId(deletedByTx),
                        MvccCommitSequence.NONE,
                        flags),
                MvccRowPayloadCodec.encode(MvccRowPayload.ofString(key, value)));
    }
}
