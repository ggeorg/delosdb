package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordFlags;

/** Crash-boundary proofs for the strict page-mutation + transaction-outcome recovery path. */
final class MvccCrashBoundaryRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void committedInsertSurvivesCrashAfterOutcomeBeforePageStoreAppend() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");

        MvccPageMutationLog.open(mutationLogFile)
                .appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendCommit(1L, 1L);

        try (PageBackedMvccTable table = PageBackedMvccTable.openStrict(
                tableFile,
                mutationLogFile,
                outcomeLogFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertEquals(1, table.physicalVersionCount());
            assertTrue(table.validateConsistency().valid());
        }
    }

    @Test
    void committedUpdateSurvivesCrashAfterOutcomeBeforeLegacyMutationCommitMarker() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile, mutationLogFile, outcomeLogFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
        }

        MvccPageMutationLog.open(mutationLogFile)
                .appendVersion(2L, version("account:1", "beta", 1L, 2L, 1L, 2L, 0L, 0));
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendCommit(2L, 2L);

        try (PageBackedMvccTable table = PageBackedMvccTable.openStrict(
                tableFile,
                mutationLogFile,
                outcomeLogFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertEquals("beta", table.read("account:1", new MvccCommitSequence(2L)).orElseThrow());
            assertEquals(2, table.physicalVersionCount("account:1"));
            assertTrue(table.validateConsistency().valid());
        }
    }

    @Test
    void abortedUpdateCrashPreservesOldCommittedVersion() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile, mutationLogFile, outcomeLogFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
        }

        MvccPageMutationLog.open(mutationLogFile)
                .appendVersion(2L, version("account:1", "beta", 1L, 2L, 1L, 2L, 0L, 0));
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendAbort(2L);

        try (PageBackedMvccTable table = PageBackedMvccTable.openStrict(
                tableFile,
                mutationLogFile,
                outcomeLogFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertEquals(1, table.physicalVersionCount("account:1"));
            assertTrue(table.validateConsistency().valid());
        }
    }

    @Test
    void committedDeleteSurvivesCrashAfterOutcomeBeforePageStoreAppend() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile, mutationLogFile, outcomeLogFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
        }

        MvccPageMutationLog.open(mutationLogFile)
                .appendVersion(2L, version("account:1", "", 1L, 2L, 1L, 2L, 2L,
                        MvccVersionRecordFlags.TOMBSTONE));
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendCommit(2L, 2L);

        try (PageBackedMvccTable table = PageBackedMvccTable.openStrict(
                tableFile,
                mutationLogFile,
                outcomeLogFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertFalse(table.read("account:1", new MvccCommitSequence(2L)).isPresent());
            assertEquals(2, table.physicalVersionCount("account:1"));
            assertTrue(table.rowDirectoryHeadForRowId(new MvccRowId(1L)).orElseThrow().tombstone());
            assertTrue(table.validateConsistency().valid());
        }
    }

    @Test
    void missingRowDirectoryIsRebuiltFromStrictRecoveredPages() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile, mutationLogFile, outcomeLogFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            table.updateCommitted("account:1", "beta", 2L, 2L);
        }
        Files.deleteIfExists(PageBackedMvccTable.rowDirectoryPath(tableFile));

        try (PageBackedMvccTable table = PageBackedMvccTable.openStrict(
                tableFile,
                mutationLogFile,
                outcomeLogFile)) {
            assertEquals("beta", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertTrue(Files.exists(PageBackedMvccTable.rowDirectoryPath(tableFile)));
            assertTrue(table.validateConsistency().valid());
        }
    }

    @Test
    void checkpointTempFilesAreIgnoredAfterCrash() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.pagemut");
        Path outcomeLogFile = tempDir.resolve("accounts.dmvcc.txoutcome");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile, mutationLogFile, outcomeLogFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            table.updateCommitted("account:1", "beta", 2L, 2L);
            table.vacuum(MvccVacuumPlan.through(2L));
        }

        Files.writeString(mutationLogFile.resolveSibling(mutationLogFile.getFileName() + ".tmp"),
                "not a complete checkpoint", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(outcomeLogFile.resolveSibling(outcomeLogFile.getFileName() + ".tmp"),
                "not a complete checkpoint", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        try (PageBackedMvccTable table = PageBackedMvccTable.openStrict(
                tableFile,
                mutationLogFile,
                outcomeLogFile)) {
            assertEquals("beta", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertTrue(table.validateConsistency().valid());
        }
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
