package io.github.ggeorg.delosdb.storage.mvcc.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 24 proof that the page-volume MVCC state-store boundary can expose selected read-only
 * checkpoint and write-ahead-log facts without claiming a WAL position or changing SQL routing.
 */
final class MvccPageVolumeResearchObservationTest {
    @TempDir
    Path tempDir;

    @Test
    void observationShowsCheckpointAndWalFileStateWithoutClaimingWalPosition() {
        String storageId = PageVolumeMvccPaths.conglomerateStorageId(7L, 42L);
        PageVolumeMvccStateStore<String> store = PageVolumeMvccStateStore.open(
                tempDir,
                storageId,
                stringCodec());
        try {
            store.persistVisibleRows(List.of(
                    new PageVolumeMvccStateStore.PersistedRow<>(1L, "alpha"),
                    new PageVolumeMvccStateStore.PersistedRow<>(2L, "bravo")));

            MvccPageVolumeResearchObservation observation =
                    MvccPageVolumeResearchObservation.capture("page-volume-mvcc", store);

            assertEquals("page-volume-mvcc", observation.subject());
            assertTrue(observation.enabled());
            assertTrue(observation.durableState());
            assertTrue(observation.pageFile().contains(storageId));
            assertTrue(observation.rowDirectoryFile().contains(storageId));
            assertTrue(observation.pageMutationLogFile().contains(storageId));
            assertTrue(observation.writeAheadLogFile().contains(storageId));
            assertEquals("PRESENT", observation.writeAheadLogState());
            assertTrue(observation.checkpointFile().contains(storageId));
            assertEquals("WRITTEN", observation.checkpointState());
            assertEquals(2, observation.logicalRows());
            assertEquals(2, observation.physicalVersions());
            assertEquals(3L, observation.nextInheritedRowId());

            String text = observation.format();
            assertTrue(text.contains("write-ahead log state: PRESENT"));
            assertTrue(text.contains("checkpoint state: WRITTEN"));
            assertTrue(text.contains("logical rows: 2"));
        } finally {
            store.close();
        }

        PageVolumeMvccStateStore<String> reopened = PageVolumeMvccStateStore.open(
                tempDir,
                storageId,
                stringCodec());
        try {
            MvccPageVolumeResearchObservation observation =
                    MvccPageVolumeResearchObservation.capture("reopened-page-volume-mvcc", reopened);

            assertEquals("VALID", observation.checkpointState(),
                    "a rewritten checkpoint should validate on reopen");
            assertEquals("PRESENT", observation.writeAheadLogState());
            assertEquals(2, observation.logicalRows());
            assertEquals(2, observation.physicalVersions());
        } finally {
            reopened.close();
        }
    }

    private static PageVolumeMvccStateStore.RowCodec<String> stringCodec() {
        return new PageVolumeMvccStateStore.RowCodec<>() {
            @Override
            public byte[] encode(String values) {
                return values.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String decode(byte[] encoded) {
                return new String(encoded, StandardCharsets.UTF_8);
            }
        };
    }
}
