package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

/** A50 proof that strict outcome-log recovery never exposes orphaned mutations. */
final class MvccUnresolvedOutcomeRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void strictRecoveryFailsForInsertMutationWithoutOutcomeAndDoesNotMaterializeIt() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.log");
        Path outcomeLogFile = tempDir.resolve("accounts.txoutcome.log");

        MvccPageMutationLog mutationLog = MvccPageMutationLog.open(mutationLogFile);
        mutationLog.appendVersion(10L, version("account:lost", "orphan", 1L, 1L, 0L, 10L, 0L, 0));

        assertThrows(MvccUnresolvedTransactionOutcomeException.class,
                () -> MvccPageRecoveryRunner.recoverStrict(mutationLogFile, outcomeLogFile, tableFile),
                "strict recovery must fail loudly when an inserted version has no durable transaction outcome");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals(0, table.physicalVersionCount());
            assertFalse(table.read("account:lost", new MvccCommitSequence(100L)).isPresent());
        }
    }

    @Test
    void strictRecoveryFailsForDeleteMutationWithoutOutcomeAndPreservesExistingRow() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
        }

        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.log");
        Path outcomeLogFile = tempDir.resolve("accounts.txoutcome.log");
        MvccPageMutationLog mutationLog = MvccPageMutationLog.open(mutationLogFile);
        mutationLog.appendVersion(20L, version("account:1", "", 1L, 2L, 1L, 20L, 20L,
                MvccVersionRecordFlags.TOMBSTONE));

        assertThrows(MvccUnresolvedTransactionOutcomeException.class,
                () -> MvccPageRecoveryRunner.recoverStrict(mutationLogFile, outcomeLogFile, tableFile),
                "strict recovery must fail loudly when a delete tombstone has no durable transaction outcome");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertEquals(1, table.physicalVersionCount("account:1"));
        }
    }

    @Test
    void committedOutcomesMaterializeInsertAndDeleteAfterRecovery() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.log");
        Path outcomeLogFile = tempDir.resolve("accounts.txoutcome.log");

        MvccPageMutationLog mutationLog = MvccPageMutationLog.open(mutationLogFile);
        mutationLog.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        mutationLog.appendVersion(2L, version("account:1", "", 1L, 2L, 1L, 2L, 2L,
                MvccVersionRecordFlags.TOMBSTONE));

        MvccTransactionOutcomeLog outcomeLog = MvccTransactionOutcomeLog.open(outcomeLogFile);
        outcomeLog.appendCommit(1L, 1L);
        outcomeLog.appendCommit(2L, 2L);

        MvccPageRecoveryRunner.RecoveryResult recovered =
                MvccPageRecoveryRunner.recoverStrict(mutationLogFile, outcomeLogFile, tableFile);
        assertEquals(2, recovered.appliedRecords());
        assertEquals(0, recovered.skippedExistingRecords());

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertFalse(table.read("account:1", new MvccCommitSequence(2L)).isPresent());
        }
    }

    @Test
    void abortedOutcomesSuppressInsertAndDeleteMutations() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.log");
        Path outcomeLogFile = tempDir.resolve("accounts.txoutcome.log");

        MvccPageMutationLog mutationLog = MvccPageMutationLog.open(mutationLogFile);
        mutationLog.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        mutationLog.appendVersion(2L, version("account:2", "bravo", 2L, 2L, 0L, 2L, 0L, 0));
        mutationLog.appendVersion(3L, version("account:1", "", 1L, 3L, 1L, 3L, 3L,
                MvccVersionRecordFlags.TOMBSTONE));

        MvccTransactionOutcomeLog outcomeLog = MvccTransactionOutcomeLog.open(outcomeLogFile);
        outcomeLog.appendCommit(1L, 1L);
        outcomeLog.appendAbort(2L);
        outcomeLog.appendAbort(3L);

        MvccPageRecoveryRunner.RecoveryResult recovered =
                MvccPageRecoveryRunner.recoverStrict(mutationLogFile, outcomeLogFile, tableFile);
        assertEquals(1, recovered.appliedRecords(), "only the committed insert should materialize");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(100L)).orElseThrow());
            assertFalse(table.read("account:2", new MvccCommitSequence(100L)).isPresent());
            assertEquals(1, table.physicalVersionCount());
        }
    }

    @Test
    void strictRecoveryIsIdempotentAndLegacyRecoveryRemainsCompatible() throws Exception {
        Path tableFile = tempDir.resolve("accounts.dmvcc");
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.log");
        Path outcomeLogFile = tempDir.resolve("accounts.txoutcome.log");

        MvccPageMutationLog mutationLog = MvccPageMutationLog.open(mutationLogFile);
        mutationLog.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        mutationLog.appendCommit(1L, 1L);

        MvccTransactionOutcomeLog outcomeLog = MvccTransactionOutcomeLog.open(outcomeLogFile);
        outcomeLog.appendCommit(1L, 1L);

        assertEquals(1, MvccPageRecoveryRunner.recoverStrict(mutationLogFile, outcomeLogFile, tableFile).appliedRecords());
        MvccPageRecoveryRunner.RecoveryResult second =
                MvccPageRecoveryRunner.recoverStrict(mutationLogFile, outcomeLogFile, tableFile);
        assertEquals(0, second.appliedRecords());
        assertEquals(1, second.skippedExistingRecords());

        Path legacyTableFile = tempDir.resolve("legacy-compatible.dmvcc");
        MvccPageMutationLog legacy = MvccPageMutationLog.open(tempDir.resolve("legacy-compatible.dmvcc.log"));
        legacy.appendVersion(10L, version("account:legacy", "uncommitted", 10L, 10L, 0L, 10L, 0L, 0));
        MvccPageRecoveryRunner.RecoveryResult legacyResult =
                MvccPageRecoveryRunner.recover(legacy.path(), legacyTableFile);
        assertEquals(0, legacyResult.appliedRecords(),
                "A50 must not retroactively make the legacy recovery path strict");
    }

    @Test
    void strictRecoveryRejectsMutationWhoseLoggedTransactionDoesNotMatchRecordCreator() {
        Path mutationLogFile = tempDir.resolve("accounts.dmvcc.log");
        Path outcomeLogFile = tempDir.resolve("accounts.txoutcome.log");
        MvccPageMutationLog mutationLog = MvccPageMutationLog.open(mutationLogFile);
        mutationLog.appendVersion(99L, version("account:bad", "mismatch", 1L, 1L, 0L, 1L, 0L, 0));
        MvccTransactionOutcomeLog.open(outcomeLogFile).appendCommit(1L, 1L);

        assertThrows(IllegalStateException.class,
                () -> mutationLog.recoverRecordsThroughOutcomeLog(MvccTransactionOutcomeLog.open(outcomeLogFile)),
                "strict recovery must reject logs whose transaction field disagrees with the record creator");
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
