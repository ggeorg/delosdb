package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.io.volume.FaultInjectingPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.OffHeapPageVolume;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordFlags;

/**
 * S9 recovery proof smoke that uses the storage I/O fault-injection decorator.
 *
 * <p>The fault volume remains I/O-level only. These tests verify that page-backed
 * recovery observes write/force failures and that strict outcome recovery still
 * suppresses or rejects records before the page store materializes them.</p>
 */
final class PageBackedMvccFaultRecoverySmoke {
    @TempDir
    Path tempDir;

    @Test
    void committedLegacyRecoveryReplaysThroughInjectedPageVolume() throws Exception {
        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("accounts.dmvcc.log"));
        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendCommit(1L, 1L);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(
                tempDir.resolve("accounts.dmvcc"),
                FaultInjectingPageVolume.wrap(OffHeapPageVolume.open()))) {
            MvccPageRecoveryRunner.RecoveryResult recovered = new MvccPageRecoveryRunner(log, store).recover();

            assertEquals(1, recovered.appliedRecords());
            assertEquals(0, recovered.skippedExistingRecords());
            assertEquals(1, store.loadAll().size());
            MvccVersionRecord stored = store.loadAll().get(0).record();
            assertEquals(new MvccCommitSequence(1L), stored.header().commitSequence());
            assertEquals("alpha", payloadValue(stored));
        }
    }

    @Test
    void legacyRecoveryDetectsInjectedWriteFailureAndDoesNotMaterializeRecord() throws Exception {
        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("write-fail.dmvcc.log"));
        log.appendVersion(1L, version("account:write", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendCommit(1L, 1L);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(
                tempDir.resolve("write-fail.dmvcc"),
                FaultInjectingPageVolume.wrap(
                        OffHeapPageVolume.open(),
                        FaultInjectingPageVolume.FaultSchedule.failOnWrite(1)))) {
            assertThrows(IOException.class, () -> new MvccPageRecoveryRunner(log, store).recover());
            assertEquals(0, store.loadAll().size(), "failed page write must not materialize a recovered row");
        }
    }

    @Test
    void legacyRecoveryDetectsInjectedForceFailure() throws Exception {
        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("force-fail.dmvcc.log"));
        log.appendVersion(1L, version("account:force", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendCommit(1L, 1L);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(
                tempDir.resolve("force-fail.dmvcc"),
                FaultInjectingPageVolume.wrap(
                        OffHeapPageVolume.open(),
                        FaultInjectingPageVolume.FaultSchedule.failOnForce(1)))) {
            assertThrows(IOException.class, () -> new MvccPageRecoveryRunner(log, store).recover());
        }
    }

    @Test
    void strictRecoverySuppressesAbortedMutationWithoutTouchingFaultingWriteVolume() throws Exception {
        MvccPageMutationLog mutationLog = MvccPageMutationLog.open(tempDir.resolve("aborted.dmvcc.log"));
        mutationLog.appendVersion(2L, version("account:aborted", "bravo", 2L, 2L, 0L, 2L, 0L, 0));
        MvccTransactionOutcomeLog outcomeLog = MvccTransactionOutcomeLog.open(tempDir.resolve("aborted.txoutcome.log"));
        outcomeLog.appendAbort(2L);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(
                tempDir.resolve("aborted.dmvcc"),
                FaultInjectingPageVolume.wrap(
                        OffHeapPageVolume.open(),
                        FaultInjectingPageVolume.FaultSchedule.failOnWrite(1)))) {
            MvccPageRecoveryRunner.RecoveryResult recovered =
                    new MvccPageRecoveryRunner(mutationLog, store).recoverStrict(outcomeLog);

            assertEquals(0, recovered.appliedRecords());
            assertEquals(0, recovered.skippedExistingRecords());
            assertEquals(0, store.loadAll().size());
        }
    }

    @Test
    void strictRecoveryFailsOnUnresolvedOutcomeBeforeWritingInjectedVolume() throws Exception {
        MvccPageMutationLog mutationLog = MvccPageMutationLog.open(tempDir.resolve("unresolved.dmvcc.log"));
        mutationLog.appendVersion(3L, version("account:lost", "orphan", 3L, 3L, 0L, 3L, 0L, 0));
        MvccTransactionOutcomeLog outcomeLog = MvccTransactionOutcomeLog.open(tempDir.resolve("unresolved.txoutcome.log"));

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(
                tempDir.resolve("unresolved.dmvcc"),
                FaultInjectingPageVolume.wrap(
                        OffHeapPageVolume.open(),
                        FaultInjectingPageVolume.FaultSchedule.failOnWrite(1)))) {
            assertThrows(MvccUnresolvedTransactionOutcomeException.class,
                    () -> new MvccPageRecoveryRunner(mutationLog, store).recoverStrict(outcomeLog));
            assertEquals(0, store.loadAll().size());
        }
    }

    @Test
    void strictRecoveryDetectsCommittedWriteFailureAndDoesNotMaterializeRecord() throws Exception {
        MvccPageMutationLog mutationLog = MvccPageMutationLog.open(tempDir.resolve("strict-write-fail.dmvcc.log"));
        mutationLog.appendVersion(4L, version("account:strict", "charlie", 4L, 4L, 0L, 4L, 0L, 0));
        MvccTransactionOutcomeLog outcomeLog = MvccTransactionOutcomeLog.open(tempDir.resolve("strict-write-fail.txoutcome.log"));
        outcomeLog.appendCommit(4L, 4L);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(
                tempDir.resolve("strict-write-fail.dmvcc"),
                FaultInjectingPageVolume.wrap(
                        OffHeapPageVolume.open(),
                        FaultInjectingPageVolume.FaultSchedule.failOnWrite(1)))) {
            assertThrows(IOException.class, () -> new MvccPageRecoveryRunner(mutationLog, store).recoverStrict(outcomeLog));
            assertEquals(0, store.loadAll().size());
        }
    }

    @Test
    void strictRecoveryStillRejectsUnresolvedDeleteAndPreservesExistingCommittedRecord() throws Exception {
        MvccPageMutationLog seedLog = MvccPageMutationLog.open(tempDir.resolve("seed.dmvcc.log"));
        seedLog.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        seedLog.appendCommit(1L, 1L);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(
                tempDir.resolve("delete-unresolved.dmvcc"),
                FaultInjectingPageVolume.wrap(OffHeapPageVolume.open()))) {
            assertEquals(1, new MvccPageRecoveryRunner(seedLog, store).recover().appliedRecords());

            MvccPageMutationLog deleteLog = MvccPageMutationLog.open(tempDir.resolve("delete-unresolved.dmvcc.log"));
            deleteLog.appendVersion(5L, version("account:1", "", 1L, 2L, 1L, 5L, 5L,
                    MvccVersionRecordFlags.TOMBSTONE));
            MvccTransactionOutcomeLog outcomeLog = MvccTransactionOutcomeLog.open(
                    tempDir.resolve("delete-unresolved.txoutcome.log"));

            assertThrows(MvccUnresolvedTransactionOutcomeException.class,
                    () -> new MvccPageRecoveryRunner(deleteLog, store).recoverStrict(outcomeLog));

            assertEquals(1, store.loadAll().size());
            MvccVersionRecord stored = store.loadAll().get(0).record();
            assertFalse(stored.header().isTombstone());
            assertEquals("alpha", payloadValue(stored));
        }
    }

    private static String payloadValue(MvccVersionRecord record) {
        return MvccRowPayloadCodec.decode(record.payload()).valueAsUtf8();
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
