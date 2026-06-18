package io.github.ggeorg.delosdb.storage.mvcc.durable;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused durable-log contract for page-backed MVCC storage.
 *
 * <p>This proof stays below Derby store/SQL integration. It hardens the log
 * rules that later boot recovery and index recovery will depend on: append-only
 * mutation records, explicit durable boundaries, deterministic replay order,
 * idempotent duplicate terminal records, and torn-tail handling.</p>
 */
final class MvccPageMutationLogContractTest {
    @TempDir
    Path tempDir;

    @Test
    void committedInsertUpdateAndDeleteMutationsReplayInCommitOrder() throws Exception {
        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("page-mutations.log"));

        log.appendVersion(2L, version("account:1", "beta", 1L, 2L, 1L, 2L, 0L, 0));
        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendVersion(3L, version("account:1", "", 1L, 3L, 2L, 3L, 3L, MvccVersionRecordFlags.TOMBSTONE));

        log.appendCommit(1L, 1L);
        log.appendCommit(2L, 2L);
        log.appendCommit(3L, 3L);

        List<MvccVersionRecord> records = log.recoverCommittedRecords();
        assertEquals(3, records.size());
        assertRecord(records.get(0), "account:1", "alpha", 1L, 1L, 0);
        assertRecord(records.get(1), "account:1", "beta", 2L, 2L, 0);
        assertRecord(records.get(2), "account:1", "", 3L, 3L, MvccVersionRecordFlags.TOMBSTONE);
    }

    @Test
    void fsyncBoundaryIsRecordedAndIgnoredByRecovery() throws Exception {
        Path logFile = tempDir.resolve("page-mutations.log");
        MvccPageMutationLog log = MvccPageMutationLog.open(logFile);

        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendFsyncBoundary(1L);
        log.appendCommit(1L, 1L);

        String content = Files.readString(logFile, StandardCharsets.UTF_8);
        assertTrue(content.contains("\tFSYNC\t1\n"), "log must contain a readable durable-boundary marker");

        List<MvccVersionRecord> records = log.recoverCommittedRecords();
        assertEquals(1, records.size());
        assertRecord(records.get(0), "account:1", "alpha", 1L, 1L, 0);
    }

    @Test
    void duplicateTerminalRecordsAreIdempotent() {
        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("page-mutations.log"));

        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendCommit(1L, 1L);
        log.appendCommit(1L, 1L);
        log.appendAbort(1L);

        List<MvccVersionRecord> records = log.recoverCommittedRecords();
        assertEquals(1, records.size(), "duplicate commit/terminal records must not duplicate replayed versions");
        assertRecord(records.get(0), "account:1", "alpha", 1L, 1L, 0);
    }

    @Test
    void abortedAndUncommittedMutationsNeverReplay() {
        MvccPageMutationLog log = MvccPageMutationLog.open(tempDir.resolve("page-mutations.log"));

        log.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        log.appendAbort(1L);
        log.appendVersion(2L, version("account:2", "beta", 2L, 2L, 0L, 2L, 0L, 0));

        assertEquals(List.of(), log.recoverCommittedRecords());
    }

    @Test
    void tornFinalRecordIsIgnoredButCorruptMiddleRecordIsRejected() throws Exception {
        Path tornLogFile = tempDir.resolve("torn-tail.log");
        MvccPageMutationLog tornLog = MvccPageMutationLog.open(tornLogFile);
        tornLog.appendVersion(1L, version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0));
        Files.writeString(tornLogFile, "1\tCOMMIT\t1\t1", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertEquals(List.of(), tornLog.recoverCommittedRecords(), "torn final records must not be replayed");

        Path corruptLogFile = tempDir.resolve("corrupt-middle.log");
        Files.writeString(corruptLogFile, "1\tFSYNC\t1\n1\tCOMMIT\tbad\t1\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        MvccPageMutationLog corruptLog = MvccPageMutationLog.open(corruptLogFile);

        assertThrows(IllegalStateException.class, corruptLog::recoverCommittedRecords,
                "corrupt complete middle records must fail recovery loudly");
    }

    @Test
    void invalidAppendArgumentsAreRejectedBeforeWriting() throws Exception {
        Path logFile = tempDir.resolve("page-mutations.log");
        MvccPageMutationLog log = MvccPageMutationLog.open(logFile);

        assertThrows(IllegalArgumentException.class, () -> log.appendVersion(0L,
                version("account:1", "alpha", 1L, 1L, 0L, 1L, 0L, 0)));
        assertThrows(IllegalArgumentException.class, () -> log.appendCommit(1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> log.appendAbort(0L));
        assertThrows(IllegalArgumentException.class, () -> log.appendFsyncBoundary(0L));

        assertFalse(Files.exists(logFile), "rejected append arguments must not create a partial log file");
    }

    private static void assertRecord(
            MvccVersionRecord record,
            String expectedKey,
            String expectedValue,
            long expectedVersionId,
            long expectedCommitSequence,
            int expectedFlags) {
        MvccRowPayload payload = MvccRowPayloadCodec.decode(record.payload());
        assertEquals(expectedKey, payload.key());
        assertEquals(expectedValue, payload.valueAsUtf8());
        assertEquals(new MvccVersionId(expectedVersionId), record.header().versionId());
        assertEquals(new MvccCommitSequence(expectedCommitSequence), record.header().commitSequence());
        assertEquals(expectedFlags, record.header().flags());
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
