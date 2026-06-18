package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MVCC-19 closeout proof for the kernel invariants called out by review.
 *
 * <p>The provider-level write-conflict and stress tests cover these paths from
 * the public SPI. This test locks them directly at the low-level
 * {@link MvccVersionChain}, {@link MvccSnapshot}, and transaction-catalog
 * boundary so future cleanup or optimization work cannot accidentally weaken
 * lazy abort handling or first-committer-wins conflict detection.</p>
 */
public final class MvccKernelReviewCloseoutTest {
    @Test
    public void staleSnapshotWriterConflictsWithoutAppendingReplacementVersion() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "v1");

        MvccTransaction staleWriter = txManager.begin();
        MvccSnapshot staleSnapshot = txManager.snapshot(staleWriter);

        MvccTransaction winner = txManager.begin();
        table.update(1, "winner", winner, txManager.snapshot(winner), txManager);
        txManager.commit(winner);

        assertEquals(Optional.of("v1"), table.read(1, staleSnapshot, txManager),
                "the stale snapshot keeps seeing the old version");
        int versionsBeforeConflict = table.physicalVersionCount(1);

        assertThrows(MvccWriteConflictException.class,
                () -> table.update(1, "loser", staleWriter, staleSnapshot, txManager),
                "a stale writer must not overwrite a version boundary claimed by a committed writer");

        assertEquals(versionsBeforeConflict, table.physicalVersionCount(1),
                "a failed update must not append a replacement ghost version");
        assertEquals(Optional.of("v1"), table.read(1, staleSnapshot, txManager),
                "the failed stale write must not disturb the stale reader view");

        txManager.abort(staleWriter);
        MvccTransaction reader = txManager.begin();
        assertEquals(Optional.of("winner"), table.read(1, txManager.snapshot(reader), txManager),
                "fresh snapshots see the committed winner only");
        txManager.abort(reader);
    }

    @Test
    public void abortedUpdateReplacementIsHiddenAndLaterWriterCanReuseOriginalVersion() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "v1");

        MvccTransaction abortedUpdater = txManager.begin();
        table.update(1, "aborted-v2", abortedUpdater, txManager.snapshot(abortedUpdater), txManager);
        txManager.abort(abortedUpdater);

        MvccTransaction readerAfterAbort = txManager.begin();
        assertEquals(Optional.of("v1"), table.read(1, txManager.snapshot(readerAfterAbort), txManager),
                "the replacement created by an aborted update is invisible");
        txManager.abort(readerAfterAbort);

        MvccTransaction nextWriter = txManager.begin();
        table.update(1, "v3", nextWriter, txManager.snapshot(nextWriter), txManager);
        txManager.commit(nextWriter);

        MvccTransaction readerAfterRetry = txManager.begin();
        assertEquals(Optional.of("v3"), table.read(1, txManager.snapshot(readerAfterRetry), txManager),
                "a later committed writer can reuse the original version after the abort marker");
        txManager.abort(readerAfterRetry);

        assertEquals(2, table.cleanup(txManager).removedVersions(),
                "cleanup prunes the aborted replacement and the superseded original version");
        assertEquals(1, table.physicalVersionCount(1));

        MvccTransaction readerAfterCleanup = txManager.begin();
        assertEquals(Optional.of("v3"), table.read(1, txManager.snapshot(readerAfterCleanup), txManager));
        txManager.abort(readerAfterCleanup);
    }

    @Test
    public void abortedDeleteMarkerCanBeReplacedByCommittedDeleteAndVacuumed() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "live");

        MvccTransaction abortedDeleter = txManager.begin();
        table.delete(1, abortedDeleter, txManager.snapshot(abortedDeleter), txManager);
        txManager.abort(abortedDeleter);

        MvccTransaction readerAfterAbort = txManager.begin();
        assertEquals(Optional.of("live"), table.read(1, txManager.snapshot(readerAfterAbort), txManager),
                "an aborted delete marker does not hide the row from new snapshots");
        txManager.abort(readerAfterAbort);

        MvccTransaction committedDeleter = txManager.begin();
        table.delete(1, committedDeleter, txManager.snapshot(committedDeleter), txManager);
        txManager.commit(committedDeleter);

        MvccTransaction readerAfterDelete = txManager.begin();
        assertEquals(Optional.empty(), table.read(1, txManager.snapshot(readerAfterDelete), txManager),
                "the later committed delete replaces the aborted delete marker");
        txManager.abort(readerAfterDelete);

        assertEquals(1, table.cleanup(txManager).removedVersions(),
                "vacuum can prune the committed-deleted physical version once no active snapshot needs it");
        assertEquals(0, table.physicalVersionCount(1));
        assertEquals(0, table.logicalRowCount());
    }

    private static MvccTable<Integer, String> seed(MvccTransactionManager txManager, String value) {
        MvccTable<Integer, String> table = new MvccTable<>();
        MvccTransaction seed = txManager.begin();
        table.insert(1, value, seed);
        txManager.commit(seed);
        return table;
    }
}
