package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;

final class MvccDurableVacuumTest {
    private static final Function<MvccRowPayload, Object> VALUE_INDEX_KEY = MvccRowPayload::valueAsUtf8;

    @TempDir
    Path tempDir;

    @Test
    void oldSnapshotProtectsOldVersionAndIndexCandidate() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        Path indexFile = tempDir.resolve("table_name.dmvcci");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile);
                MvccIndexStore index = MvccIndexStore.open(indexFile)) {
            MvccIndexTuple alpha = table.insertCommitted("account:1", "alpha", 1L, 1L);
            index.appendCandidate("alpha", alpha);
            MvccIndexTuple beta = table.updateCommitted("account:1", "beta", 2L, 2L);
            index.appendCandidate("beta", beta);

            MvccVacuumResult protectedResult = MvccVacuum.vacuum(table, MvccVacuumPlan.through(1L), index);
            assertEquals(0, protectedResult.removedVersions());
            assertEquals(0, protectedResult.removedIndexCandidates());
            assertEquals(2, protectedResult.remainingVersions());
            assertEquals(1, index.candidateCount("alpha"));
            assertEquals("alpha", table.readVisibleIndexCandidate(
                    index.lookupCandidates("alpha").get(0), new MvccCommitSequence(1L), VALUE_INDEX_KEY, "alpha")
                    .orElseThrow().valueAsUtf8());

            MvccVacuumResult result = MvccVacuum.vacuum(table, MvccVacuumPlan.through(2L), index);
            assertEquals(1, result.removedVersions());
            assertEquals(1, result.removedIndexCandidates());
            assertEquals(0, result.removedLogicalRows());
            assertEquals(1, result.remainingVersions());
            assertEquals(1, result.remainingLogicalRows());
            assertEquals(0, index.candidateCount("alpha"));
            assertEquals(1, index.candidateCount("beta"));
            assertEquals("beta", table.readVisibleIndexCandidate(
                    index.lookupCandidates("beta").get(0), new MvccCommitSequence(2L), VALUE_INDEX_KEY, "beta")
                    .orElseThrow().valueAsUtf8());
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile);
                MvccIndexStore index = MvccIndexStore.open(indexFile)) {
            assertEquals(1, table.physicalVersionCount());
            assertEquals(0, index.candidateCount("alpha"));
            assertEquals(1, index.candidateCount("beta"));
            assertEquals("beta", table.read("account:1", new MvccCommitSequence(2L)).orElseThrow());
        }
    }

    @Test
    void deleteTombstoneIsCleanedAfterProtectingSnapshotIsGone() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        Path indexFile = tempDir.resolve("table_name.dmvcci");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile);
                MvccIndexStore index = MvccIndexStore.open(indexFile)) {
            MvccIndexTuple alpha = table.insertCommitted("account:1", "alpha", 1L, 1L);
            index.appendCandidate("alpha", alpha);
            table.deleteCommitted("account:1", 2L, 2L);

            MvccVacuumResult protectedResult = MvccVacuum.vacuum(table, MvccVacuumPlan.through(1L), index);
            assertEquals(0, protectedResult.removedVersions());
            assertEquals(0, protectedResult.removedIndexCandidates());
            assertEquals(2, protectedResult.remainingVersions());
            assertEquals(1, index.candidateCount("alpha"));

            MvccVacuumResult result = MvccVacuum.vacuum(table, MvccVacuumPlan.through(2L), index);
            assertEquals(2, result.removedVersions());
            assertEquals(1, result.removedIndexCandidates());
            assertEquals(1, result.removedLogicalRows());
            assertEquals(0, result.remainingVersions());
            assertEquals(0, result.remainingLogicalRows());
            assertEquals(0, table.physicalVersionCount());
            assertEquals(0, table.logicalRowCount());
            assertEquals(0, index.candidateCount());
            assertFalse(table.read("account:1", new MvccCommitSequence(2L)).isPresent());
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile);
                MvccIndexStore index = MvccIndexStore.open(indexFile)) {
            assertEquals(0, table.physicalVersionCount());
            assertEquals(0, table.logicalRowCount());
            assertEquals(0, index.candidateCount());
            assertFalse(table.read("account:1", new MvccCommitSequence(2L)).isPresent());
        }
    }

    @Test
    void vacuumRewritesRecoveryCheckpointToRetainedDurableImage() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        Path logFile = tempDir.resolve("table.dmvcc.log");
        Path indexFile = tempDir.resolve("table_name.dmvcci");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile, logFile);
                MvccIndexStore index = MvccIndexStore.open(indexFile)) {
            index.appendCandidate("alpha", table.insertCommitted("account:1", "alpha", 1L, 1L));
            index.appendCandidate("beta", table.updateCommitted("account:1", "beta", 2L, 2L));
            MvccVacuumResult result = MvccVacuum.vacuum(table, MvccVacuumPlan.through(2L), index);
            assertEquals(1, result.removedVersions());
            assertEquals(1, result.removedIndexCandidates());
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile, logFile);
                MvccIndexStore index = MvccIndexStore.open(indexFile)) {
            assertEquals(1, table.physicalVersionCount());
            assertEquals(0, index.candidateCount("alpha"));
            assertEquals(1, index.candidateCount("beta"));
            assertEquals("beta", table.read("account:1", new MvccCommitSequence(2L)).orElseThrow());
        }
    }
}
