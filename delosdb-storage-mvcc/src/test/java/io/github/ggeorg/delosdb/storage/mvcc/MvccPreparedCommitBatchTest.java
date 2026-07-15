package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proofs for staging commit sequences before terminal status publication. */
final class MvccPreparedCommitBatchTest {
    @Test
    void preparationDoesNotAdvanceVisibilityOrEndTransactions() {
        MvccTransactionManager manager = new MvccTransactionManager();
        MvccTransaction first = manager.begin();
        MvccTransaction second = manager.begin();

        MvccTransactionManager.PreparedCommitBatch prepared =
                manager.prepareCommitBatch(List.of(first, second));

        assertEquals(MvccCommitSequence.NONE, manager.newestCommitSequence());
        assertEquals(2, manager.activeTransactionCount());
        assertEquals(List.of(new MvccCommitSequence(1L), new MvccCommitSequence(2L)), prepared.sequences());

        manager.publishPreparedCommitBatch(prepared);

        assertEquals(new MvccCommitSequence(2L), manager.newestCommitSequence());
        assertEquals(0, manager.activeTransactionCount());
        assertEquals(MvccTransactionStatus.COMMITTED, manager.statusOf(first.id()));
        assertEquals(MvccTransactionStatus.COMMITTED, manager.statusOf(second.id()));
    }

    @Test
    void stalePreparedBatchCannotPublishAfterAnotherCommit() {
        MvccTransactionManager manager = new MvccTransactionManager();
        MvccTransaction staged = manager.begin();
        MvccTransactionManager.PreparedCommitBatch prepared =
                manager.prepareCommitBatch(List.of(staged));

        MvccTransaction other = manager.begin();
        manager.commit(other);

        assertThrows(
                IllegalStateException.class,
                () -> manager.publishPreparedCommitBatch(prepared));
        assertEquals(1, manager.activeTransactionCount());
        manager.abort(staged);
    }
}
