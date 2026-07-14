package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;

/** Phase 7.3 all-or-none recovery proofs for the transaction outcome fence. */
final class MvccTransactionCommitFenceRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void preparedTransactionWithoutOutcomeFenceIsIgnoredAsUncommitted() throws Exception {
        Paths paths = paths("unfenced");
        MvccPageMutationLog.open(paths.mutationLog())
                .appendPreparedTransaction(1L, 1L, records(1L, 1L, 3));

        MvccPageRecoveryRunner.RecoveryResult result = MvccPageRecoveryRunner.recoverStrict(
                paths.mutationLog(), paths.outcomeLog(), paths.table());

        assertEquals(0, result.appliedRecords());
        try (PageBackedMvccTable table = PageBackedMvccTable.open(paths.table())) {
            assertEquals(0, table.physicalVersionCount());
        }
    }

    @Test
    void committedOutcomeReplaysTheWholePreparedTransaction() throws Exception {
        Paths paths = paths("committed");
        List<MvccVersionRecord> records = records(2L, 2L, 8);
        MvccPageMutationLog.open(paths.mutationLog())
                .appendPreparedTransaction(2L, 2L, records);
        MvccTransactionOutcomeLog.open(paths.outcomeLog()).appendCommit(2L, 2L);

        MvccPageRecoveryRunner.RecoveryResult result = MvccPageRecoveryRunner.recoverStrict(
                paths.mutationLog(), paths.outcomeLog(), paths.table());

        assertEquals(8, result.appliedRecords());
        try (PageBackedMvccTable table = PageBackedMvccTable.open(paths.table())) {
            assertEquals(8, table.physicalVersionCount());
            assertEquals(8, table.logicalRowCount());
        }
    }

    @Test
    void committedOutcomeRejectsATornPreparedBatchInsteadOfReplayingAPrefix() throws Exception {
        Paths paths = paths("torn");
        MvccPageMutationLog.open(paths.mutationLog())
                .appendPreparedTransaction(3L, 3L, records(3L, 3L, 4));
        List<String> complete = Files.readAllLines(paths.mutationLog(), StandardCharsets.UTF_8);
        Files.write(paths.mutationLog(), complete.subList(0, complete.size() - 1), StandardCharsets.UTF_8);
        MvccTransactionOutcomeLog.open(paths.outcomeLog()).appendCommit(3L, 3L);

        assertThrows(IllegalStateException.class, () -> MvccPageRecoveryRunner.recoverStrict(
                paths.mutationLog(), paths.outcomeLog(), paths.table()));

        try (PageBackedMvccTable table = PageBackedMvccTable.open(paths.table())) {
            assertEquals(0, table.physicalVersionCount(),
                    "a committed outcome with an incomplete payload batch must not materialize a prefix");
        }
    }

    @Test
    void recoveryCompletesAPartiallyMaterializedCommittedTransaction() throws Exception {
        Paths paths = paths("partial-pages");
        List<MvccVersionRecord> records = records(4L, 4L, 3);
        MvccPageMutationLog.open(paths.mutationLog())
                .appendPreparedTransaction(4L, 4L, records);
        MvccTransactionOutcomeLog.open(paths.outcomeLog()).appendCommit(4L, 4L);
        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(paths.table())) {
            store.append(records.getFirst());
        }

        MvccPageRecoveryRunner.RecoveryResult result = MvccPageRecoveryRunner.recoverStrict(
                paths.mutationLog(), paths.outcomeLog(), paths.table());

        assertEquals(2, result.appliedRecords());
        assertEquals(1, result.skippedExistingRecords());
        try (PageBackedMvccTable table = PageBackedMvccTable.open(paths.table())) {
            assertEquals(3, table.physicalVersionCount());
            assertEquals(3, table.logicalRowCount());
            table.validateConsistency().assertValid();
        }
    }

    private Paths paths(String name) {
        return new Paths(
                tempDir.resolve(name + ".dmvcc"),
                tempDir.resolve(name + ".dmvcc.pagemut"),
                tempDir.resolve(name + ".dmvcc.txoutcome"));
    }

    private static List<MvccVersionRecord> records(long transactionId, long commitSequence, int count) {
        List<MvccVersionRecord> records = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            records.add(new MvccVersionRecord(
                    new MvccTupleHeader(
                            new MvccRowId(index),
                            new MvccVersionId(index),
                            MvccVersionId.NONE,
                            new MvccTransactionId(transactionId),
                            MvccTransactionId.NONE,
                            new MvccCommitSequence(commitSequence),
                            0),
                    MvccRowPayloadCodec.encode(MvccRowPayload.ofString("row:" + index, "value:" + index))));
        }
        return List.copyOf(records);
    }

    private record Paths(Path table, Path mutationLog, Path outcomeLog) {
    }
}
