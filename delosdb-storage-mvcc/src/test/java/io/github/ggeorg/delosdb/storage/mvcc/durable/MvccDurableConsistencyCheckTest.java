package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;

final class MvccDurableConsistencyCheckTest {
    @TempDir
    Path tempDir;

    @Test
    void cleanPageBackedTablePassesConsistencyCheck() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        Path logFile = tempDir.resolve("table.dmvcc.log");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile, logFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            MvccVersionId first = table.newestVersionIdForKey("account:1").orElseThrow();
            table.updateCommittedIfCurrentVersion("account:1", "bravo", first, 2L, 2L);

            MvccDurableConsistencyCheck.Result result = table.validateConsistency();
            assertTrue(result.valid());
            assertEquals(2, result.physicalVersions());
            assertEquals(1, result.logicalRows());
            assertEquals(1, result.durableHeads());
        }

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile, logFile)) {
            assertEquals("bravo", reopened.read("account:1", new MvccCommitSequence(10L)).orElseThrow());
            assertTrue(reopened.validateConsistency().valid());
        }
    }

    @Test
    void missingPreviousVersionIsReported() throws Exception {
        PageBackedMvccTableStore.StoredVersionRecord orphan = stored(
                "account:1", "orphan", 1L, 2L, 1L, 1L, 1L);
        MvccDurableConsistencyCheck.Result result = MvccDurableConsistencyCheck.check(
                List.of(orphan), Map.of());

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("missing previous version")));
    }

    @Test
    void staleRowDirectoryHeadIsReportedBeforeBootReconciliation() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        Path logFile = tempDir.resolve("table.dmvcc.log");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile, logFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
        }

        Path rowDirectory = PageBackedMvccTable.rowDirectoryPath(tableFile);
        MvccRowDirectoryStore directoryStore = MvccRowDirectoryStore.open(rowDirectory);
        MvccRowDirectoryStore.RowHeadRecord head = directoryStore.recoverHeads().values().iterator().next();
        directoryStore.rewriteHeads(List.of(new MvccRowDirectoryStore.RowHeadRecord(
                head.rowId(),
                "account:wrong",
                head.headVersionId(),
                head.previousVersionId(),
                head.headLocator(),
                head.tombstone())));

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tableFile)) {
            MvccDurableConsistencyCheck.Result result = MvccDurableConsistencyCheck.check(store, directoryStore);
            assertFalse(result.valid());
            assertTrue(result.errors().stream().anyMatch(error -> error.contains("account:wrong")));
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile, logFile)) {
            assertTrue(table.validateConsistency().valid());
        }
    }

    private static PageBackedMvccTableStore.StoredVersionRecord stored(
            String key,
            String value,
            long rowId,
            long versionId,
            long previousVersionId,
            long transactionId,
            long commitSequence) {
        MvccVersionRecord record = new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(rowId),
                        new MvccVersionId(versionId),
                        previousVersionId == 0L ? MvccVersionId.NONE : new MvccVersionId(previousVersionId),
                        new MvccTransactionId(transactionId),
                        MvccTransactionId.NONE,
                        new MvccCommitSequence(commitSequence),
                        0),
                MvccRowPayloadCodec.encode(new MvccRowPayload(key, value.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return new PageBackedMvccTableStore.StoredVersionRecord(
                new MvccVersionLocator(new io.github.ggeorg.delosdb.storage.io.page.DelosPageId(0L), (int) versionId),
                record);
    }
}
