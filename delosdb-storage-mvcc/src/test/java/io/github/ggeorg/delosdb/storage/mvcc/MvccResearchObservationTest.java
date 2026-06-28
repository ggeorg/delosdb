package io.github.ggeorg.delosdb.storage.mvcc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 24 proof that the native MVCC path can expose reader-facing internal facts without
 * changing storage behavior or depending on the engine trace package.
 */
public final class MvccResearchObservationTest {
    @Test
    public void observationShowsSnapshotVisibilityAndVersionCounts() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction seed = txManager.begin();
        table.insert(1, "one", seed);
        table.insert(2, "two", seed);
        txManager.commit(seed);

        MvccTransaction reader = txManager.begin();
        MvccSnapshot readerSnapshot = txManager.snapshot(reader);

        MvccTransaction laterWriter = txManager.begin();
        table.insert(3, "three", laterWriter);
        txManager.commit(laterWriter);

        MvccResearchObservation observation = MvccResearchObservation.capture(
                "mvcc-table", table, txManager, readerSnapshot);

        assertEquals("mvcc-table", observation.subject());
        assertEquals(reader.id().value(), observation.ownerTransactionId());
        assertEquals(1L, observation.visibleThroughCommitSequence());
        assertEquals("latest", observation.visibleThroughCommandSequence());
        assertEquals(0, observation.activeAtCapture());
        assertEquals(2, observation.visibleRows(),
                "the old snapshot must not see the row committed after capture");
        assertEquals(3, observation.logicalRows());
        assertEquals(3, observation.physicalVersions());
        assertEquals(2L, observation.newestCommitSequence());
        assertEquals(1L, observation.oldestRetainedVisibleThrough());
        assertEquals(1, observation.activeTransactions());
        assertEquals(0, observation.retainedSnapshots());
        assertEquals("NOT_OBSERVED", observation.walPosition());
        assertEquals("NOT_OBSERVED", observation.checkpointState());

        String text = observation.format();
        assertTrue(text.contains("visible rows: 2"));
        assertTrue(text.contains("logical rows: 3"));
        assertTrue(text.contains("physical versions: 3"));
        assertTrue(text.contains("wal position: NOT_OBSERVED"));

        txManager.abort(reader);
    }
}
