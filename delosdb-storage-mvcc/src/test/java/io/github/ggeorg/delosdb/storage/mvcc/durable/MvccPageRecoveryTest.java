package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordFlags;

final class MvccPageRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void committedPageMutationReplaysIntoPageStore() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("table.dmvcc.log"));
        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendCommit(1L, 1L);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tableFile)) {
            MvccPageRecoveryRunner.RecoveryResult result = new MvccPageRecoveryRunner(log, store).recover();
            assertEquals(1, result.appliedRecords());
            assertEquals(0, result.skippedExistingRecords());
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
        }
    }

    @Test
    void versionWithoutCommitIsIgnoredAfterCrash() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("table.dmvcc.log"));
        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tableFile)) {
            assertEquals(0, new MvccPageRecoveryRunner(log, store).recover().appliedRecords());
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertFalse(table.read("account:1", new MvccCommitSequence(100L)).isPresent());
            assertEquals(0, table.physicalVersionCount());
        }
    }

    @Test
    void abortedPageMutationIsIgnoredAfterCrash() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("table.dmvcc.log"));
        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendAbort(1L);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tableFile)) {
            assertEquals(0, new MvccPageRecoveryRunner(log, store).recover().appliedRecords());
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertFalse(table.read("account:1", new MvccCommitSequence(100L)).isPresent());
        }
    }

    @Test
    void crashDuringUpdatePreservesOldCommittedVersion() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
        }

        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("table.dmvcc.log"));
        log.appendVersion(2L, version("account:1", "beta", 1L, 2L, 1L, 2L, 0L, 0));

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tableFile)) {
            assertEquals(0, new MvccPageRecoveryRunner(log, store).recover().appliedRecords());
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertEquals(1, table.physicalVersionCount("account:1"));
        }
    }

    @Test
    void committedUpdateReplaysAsNewVisibleVersion() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
        }

        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("table.dmvcc.log"));
        log.appendVersion(2L, version("account:1", "beta", 1L, 2L, 1L, 2L, 0L, 0));
        log.appendCommit(2L, 2L);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tableFile)) {
            assertEquals(1, new MvccPageRecoveryRunner(log, store).recover().appliedRecords());
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertEquals("beta", table.read("account:1", new MvccCommitSequence(2L)).orElseThrow());
            assertEquals(2, table.physicalVersionCount("account:1"));
        }
    }

    @Test
    void committedDeleteReplaysAsTombstone() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
        }

        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("table.dmvcc.log"));
        log.appendVersion(2L, version("account:1", "", 1L, 2L, 1L, 2L, 2L, MvccVersionRecordFlags.TOMBSTONE));
        log.appendCommit(2L, 2L);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tableFile)) {
            assertEquals(1, new MvccPageRecoveryRunner(log, store).recover().appliedRecords());
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertFalse(table.read("account:1", new MvccCommitSequence(2L)).isPresent());
        }
    }

    @Test
    void recoveryIsIdempotentWhenRunMoreThanOnce() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("table.dmvcc.log"));
        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendCommit(1L, 1L);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tableFile)) {
            assertEquals(1, new MvccPageRecoveryRunner(log, store).recover().appliedRecords());
            MvccPageRecoveryRunner.RecoveryResult second = new MvccPageRecoveryRunner(log, store).recover();
            assertEquals(0, second.appliedRecords());
            assertEquals(1, second.skippedExistingRecords());
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals(1, table.physicalVersionCount("account:1"));
        }
    }

    @Test
    void tornFinalLogRecordIsIgnored() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        Path logFile = tempDir.resolve("table.dmvcc.log");
        MvccPageMutationLog log = MvccPageMutationLog.open(logFile);
        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        Files.writeString(logFile, "1\tCOMMIT\t1\t1", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tableFile)) {
            assertEquals(0, new MvccPageRecoveryRunner(log, store).recover().appliedRecords());
        }
    }

    @Test
    void corruptMiddleLogRecordIsRejected() throws Exception {
        Path logFile = tempDir.resolve("table.dmvcc.log");
        MvccPageMutationLog log = MvccPageMutationLog.open(logFile);
        Files.writeString(logFile, "1\tCOMMIT\tbad\t1\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE);

        assertThrows(IllegalStateException.class, log::recoverCommittedRecords);
    }

    @Test
    void checkpointRewriteCompactsCommittedImage() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("table.dmvcc.log"));
        log.appendVersion(10L, version("dead:1", "old", 1L, 1L, 0L, 10L, 0L, 0));
        log.appendAbort(10L);
        MvccVersionRecord committed = version("account:1", "alpha", 2L, 2L, 0L, 11L, 1L, 0);
        log.rewriteCheckpoint(List.of(committed));

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tableFile)) {
            assertEquals(1, new MvccPageRecoveryRunner(log, store).recover().appliedRecords());
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertFalse(table.read("dead:1", new MvccCommitSequence(100L)).isPresent());
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
