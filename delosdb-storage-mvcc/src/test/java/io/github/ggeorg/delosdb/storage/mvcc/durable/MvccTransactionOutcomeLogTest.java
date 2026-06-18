package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatus;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordFlags;

/** A49 proof for authoritative durable transaction outcomes. */
final class MvccTransactionOutcomeLogTest {
    @TempDir
    Path tempDir;

    @Test
    void committedAndAbortedOutcomesRecoverDeterministically() throws Exception {
        Path logFile = tempDir.resolve("tx-outcomes.log");
        MvccTransactionOutcomeLog log = MvccTransactionOutcomeLog.open(logFile);

        log.appendCommit(1L, 10L);
        log.appendAbort(2L);
        log.appendFsyncBoundary(1L);
        log.appendCommit(1L, 10L);

        String content = Files.readString(logFile, StandardCharsets.UTF_8);
        assertTrue(content.contains("\tFSYNC\t1\n"), "outcome log should expose readable durable-boundary markers");

        Map<MvccTransactionId, MvccTransactionOutcomeLog.Outcome> outcomes = log.recoverOutcomes();
        assertEquals(2, outcomes.size());
        assertEquals(MvccTransactionStatus.COMMITTED, outcomes.get(new MvccTransactionId(1L)).status());
        assertEquals(new MvccCommitSequence(10L), outcomes.get(new MvccTransactionId(1L)).commitSequence());
        assertEquals(MvccTransactionStatus.ABORTED, outcomes.get(new MvccTransactionId(2L)).status());
        assertEquals(MvccCommitSequence.NONE, outcomes.get(new MvccTransactionId(2L)).commitSequence());
    }

    @Test
    void strictOutcomePathStampsCommittedRecordsAndSuppressesAbortedRecords() {
        MvccTransactionOutcomeLog log = MvccTransactionOutcomeLog.open(tempDir.resolve("tx-outcomes.log"));
        log.appendCommit(1L, 20L);
        log.appendAbort(2L);

        Optional<MvccVersionRecord> committed = log.committedRecordOrEmpty(
                version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        assertTrue(committed.isPresent());
        assertEquals(new MvccCommitSequence(20L), committed.orElseThrow().header().commitSequence());

        Optional<MvccVersionRecord> aborted = log.committedRecordOrEmpty(
                version("account:2", "bravo", 2L, 2L, 0L, 2L, 0L, 0));
        assertFalse(aborted.isPresent(), "aborted creators must not materialize records in the strict outcome path");
    }

    @Test
    void strictOutcomePathFailsLoudlyForUnknownCreatorOutcome() {
        MvccTransactionOutcomeLog log = MvccTransactionOutcomeLog.open(tempDir.resolve("tx-outcomes.log"));
        log.appendCommit(1L, 1L);

        MvccVersionRecord unknownCreator = version("account:missing", "lost", 3L, 3L, 0L, 99L, 0L, 0);

        assertThrows(MvccUnresolvedTransactionOutcomeException.class,
                () -> log.committedRecordOrEmpty(unknownCreator),
                "mutations covered by the new outcome-log path need an authoritative terminal outcome");
    }

    @Test
    void conflictingCompleteOutcomeRecordsAreRejectedButTornTailIsIgnored() throws Exception {
        Path conflictFile = tempDir.resolve("conflict.log");
        Files.writeString(conflictFile, "1\tCOMMIT\t1\t1\n1\tABORT\t1\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE);
        MvccTransactionOutcomeLog conflict = MvccTransactionOutcomeLog.open(conflictFile);
        assertThrows(IllegalStateException.class, conflict::recoverOutcomes);

        Path tornTailFile = tempDir.resolve("torn-tail.log");
        Files.writeString(tornTailFile, "1\tCOMMIT\t1\t1\n1\tABORT\t2", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE);
        MvccTransactionOutcomeLog tornTail = MvccTransactionOutcomeLog.open(tornTailFile);
        assertEquals(Map.of(new MvccTransactionId(1L),
                MvccTransactionOutcomeLog.Outcome.committed(new MvccCommitSequence(1L))),
                tornTail.recoverOutcomes(), "torn final outcome records must not become authoritative");
    }

    @Test
    void invalidAppendArgumentsAreRejectedBeforeWriting() throws Exception {
        Path logFile = tempDir.resolve("tx-outcomes.log");
        MvccTransactionOutcomeLog log = MvccTransactionOutcomeLog.open(logFile);

        assertThrows(IllegalArgumentException.class, () -> log.appendCommit(0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> log.appendCommit(1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> log.appendAbort(0L));
        assertThrows(IllegalArgumentException.class, () -> log.appendFsyncBoundary(0L));

        assertFalse(Files.exists(logFile), "rejected append arguments must not create a partial outcome log");
    }

    @Test
    void legacyPageMutationLogRecoveryKeepsPreOutcomeCompatibility() {
        Path logFile = tempDir.resolve("legacy-page-mutations.log");
        MvccPageMutationLog legacy = MvccPageMutationLog.open(logFile);
        legacy.appendVersion(10L, version("account:lost", "uncommitted", 1L, 1L, 0L, 10L, 0L, 0));

        List<MvccVersionRecord> recovered = legacy.recoverCommittedRecords();
        assertEquals(List.of(), recovered,
                "A49 must not retroactively make the legacy page-mutation recovery path strict");
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
