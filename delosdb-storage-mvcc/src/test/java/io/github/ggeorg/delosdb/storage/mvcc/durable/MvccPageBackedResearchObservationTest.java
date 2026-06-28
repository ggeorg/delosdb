package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;

/**
 * Phase 24 proof that page-backed MVCC internals can expose selected read-only observation facts
 * without changing storage behavior or claiming SQL-engine routing.
 */
final class MvccPageBackedResearchObservationTest {
    @TempDir
    Path tempDir;

    @Test
    void observationShowsPageVersionAndRowDirectoryFacts() throws Exception {
        Path tableFile = tempDir.resolve("accounts.mvccp");
        Path mutationLogFile = tempDir.resolve("accounts.mvccp.log");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile, mutationLogFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            table.insertCommitted("account:2", "bravo", 2L, 2L);
            table.updateCommitted("account:1", "alpha-updated", 3L, 3L);

            MvccPageBackedResearchObservation observation = MvccPageBackedResearchObservation.capture(
                    "page-backed-mvcc", tableFile, mutationLogFile, table, new MvccCommitSequence(1L));

            assertEquals("page-backed-mvcc", observation.subject());
            assertEquals(tableFile.toString(), observation.pageFile());
            assertEquals(table.rowDirectoryPath().toString(), observation.rowDirectoryFile());
            assertEquals(mutationLogFile.toString(), observation.mutationLogFile());
            assertTrue(observation.pageCount() >= 1L);
            assertEquals(2, observation.rowDirectoryHeads());
            assertEquals(2, observation.logicalRows());
            assertEquals(3, observation.physicalVersions());
            assertEquals(1, observation.visibleRows(),
                    "a commit-sequence-1 snapshot should only see the first committed row");
            assertEquals("PRESENT", observation.mutationLogState());
            assertEquals("NOT_OBSERVED", observation.checkpointState());

            String text = observation.format();
            assertTrue(text.contains("page count: "));
            assertTrue(text.contains("row-directory heads: 2"));
            assertTrue(text.contains("physical versions: 3"));
            assertTrue(text.contains("visible rows: 1"));
            assertTrue(text.contains("checkpoint state: NOT_OBSERVED"));
        }
    }
}
