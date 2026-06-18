package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordFlags;

final class MvccRecoveryBootProofTest {
    @TempDir
    Path tempDir;

    @Test
    void committedInsertUpdateAndDeleteSurviveBootRecovery() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path logFile = tempDir.resolve("accounts.dmvcc.log");
        MvccPageMutationLog log = MvccPageMutationLog.open(logFile);
        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendVersion(1L, version("account:2", "bravo", 2L, 2L, 0L, 1L, 0L, 0));
        log.appendCommit(1L, 1L);
        log.appendVersion(2L, version("account:1", "alpha-updated", 1L, 3L, 1L, 2L, 0L, 0));
        log.appendCommit(2L, 2L);
        log.appendVersion(3L, version("account:2", "", 2L, 4L, 2L, 3L, 3L,
                MvccVersionRecordFlags.TOMBSTONE));
        log.appendCommit(3L, 3L);

        MvccPageRecoveryRunner.RecoveryResult recovered = MvccPageRecoveryRunner.recover(logFile, tableFile);
        assertEquals(4, recovered.appliedRecords());
        assertEquals(0, recovered.skippedExistingRecords());

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertEquals("alpha-updated", table.read("account:1", new MvccCommitSequence(2L)).orElseThrow());
            assertEquals("bravo", table.read("account:2", new MvccCommitSequence(2L)).orElseThrow());
            assertFalse(table.read("account:2", new MvccCommitSequence(3L)).isPresent());
            assertEquals(2, table.logicalRowCount());
            assertEquals(4, table.physicalVersionCount());
        }

        MvccPageRecoveryRunner.RecoveryResult secondBoot = MvccPageRecoveryRunner.recover(logFile, tableFile);
        assertEquals(0, secondBoot.appliedRecords());
        assertEquals(4, secondBoot.skippedExistingRecords());

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha-updated", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertFalse(table.read("account:2", new MvccCommitSequence(100L)).isPresent());
            assertEquals(4, table.physicalVersionCount());
        }
    }

    @Test
    void crashBeforeCommitDoesNotMaterializeRowsOnBoot() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path logFile = tempDir.resolve("accounts.dmvcc.log");
        MvccPageMutationLog log = MvccPageMutationLog.open(logFile);
        log.appendVersion(10L, version("account:lost", "uncommitted", 1L, 1L, 0L, 10L, 0L, 0));

        MvccPageRecoveryRunner.RecoveryResult recovered = MvccPageRecoveryRunner.recover(logFile, tableFile);
        assertEquals(0, recovered.appliedRecords());
        assertEquals(0, recovered.skippedExistingRecords());

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals(0, table.logicalRowCount());
            assertEquals(0, table.physicalVersionCount());
            assertFalse(table.read("account:lost", new MvccCommitSequence(100L)).isPresent());
        }
    }

    @Test
    void committedDeleteRemainsHiddenAfterRepeatedReopen() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path logFile = tempDir.resolve("accounts.dmvcc.log");
        MvccPageMutationLog log = MvccPageMutationLog.open(logFile);
        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendCommit(1L, 1L);
        log.appendVersion(2L, version("account:1", "", 1L, 2L, 1L, 2L, 2L,
                MvccVersionRecordFlags.TOMBSTONE));
        log.appendCommit(2L, 2L);

        assertEquals(2, MvccPageRecoveryRunner.recover(logFile, tableFile).appliedRecords());

        for (int boot = 0; boot < 3; boot++) {
            try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
                assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
                assertFalse(table.read("account:1", new MvccCommitSequence(2L)).isPresent());
            }
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
        byte[] payload = MvccRowPayloadCodec.encode(new MvccRowPayload(key, value.getBytes(StandardCharsets.UTF_8)));
        return new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(rowId),
                        new MvccVersionId(versionId),
                        previousVersionId == 0L ? MvccVersionId.NONE : new MvccVersionId(previousVersionId),
                        new MvccTransactionId(createdByTx),
                        new MvccTransactionId(deletedByTx),
                        MvccCommitSequence.NONE,
                        flags),
                payload);
    }
}
