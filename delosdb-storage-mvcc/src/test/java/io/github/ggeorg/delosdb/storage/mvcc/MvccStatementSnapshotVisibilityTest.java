package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A47 proof for statement-level MVCC snapshots.
 *
 * <p>A46 introduced command metadata. This proof closes the next boundary: the
 * transaction manager now allocates statement snapshots with monotonically
 * increasing command stamps, and table operations can use that statement object
 * directly. A statement snapshot sees earlier own commands but not the current
 * or later same-transaction update/delete.</p>
 */
public final class MvccStatementSnapshotVisibilityTest {
    @Test
    public void testStatementSnapshotBetweenOwnUpdatesKeepsEarlierValue() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction tx = txManager.begin();

        MvccStatementSnapshot insertStatement = txManager.beginStatement(tx);
        assertEquals(MvccCommandSequence.of(1L), insertStatement.commandSequence(),
                "first statement receives command sequence 1");
        table.insert(1, "A", insertStatement);
        assertEquals(Optional.empty(), table.read(1, insertStatement, txManager),
                "a statement snapshot does not see its own insert from the same command");

        MvccStatementSnapshot readBetweenUpdates = txManager.beginStatement(tx);
        assertEquals(MvccCommandSequence.of(2L), readBetweenUpdates.commandSequence(),
                "second statement receives command sequence 2");
        assertEquals(Optional.of("A"), table.read(1, readBetweenUpdates, txManager),
                "statement 2 sees the row inserted by statement 1");

        MvccStatementSnapshot updateStatement = txManager.beginStatement(tx);
        assertEquals(MvccCommandSequence.of(3L), updateStatement.commandSequence(),
                "third statement receives command sequence 3");
        table.update(1, "B", updateStatement, txManager);
        assertEquals(Optional.of("A"), table.read(1, updateStatement, txManager),
                "an update statement keeps seeing its statement-start value");
        assertEquals(Optional.of("A"), table.read(1, readBetweenUpdates, txManager),
                "older read statement snapshot does not see later own update");

        MvccStatementSnapshot laterRead = txManager.beginStatement(tx);
        assertEquals(MvccCommandSequence.of(4L), laterRead.commandSequence(),
                "fourth statement receives command sequence 4");
        assertEquals(Optional.of("B"), table.read(1, laterRead, txManager),
                "later statement snapshot sees the update from statement 3");
    }

    @Test
    public void testStatementSnapshotBeforeOwnDeleteKeepsRowVisible() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction tx = txManager.begin();

        MvccStatementSnapshot insertStatement = txManager.beginStatement(tx);
        table.insert(1, "live", insertStatement);

        MvccStatementSnapshot readBeforeDelete = txManager.beginStatement(tx);
        assertEquals(Optional.of("live"), table.read(1, readBeforeDelete, txManager),
                "statement 2 sees row inserted by statement 1");

        MvccStatementSnapshot deleteStatement = txManager.beginStatement(tx);
        table.delete(1, deleteStatement, txManager);
        assertEquals(Optional.of("live"), table.read(1, deleteStatement, txManager),
                "a delete statement keeps seeing its statement-start row");
        assertEquals(Optional.of("live"), table.read(1, readBeforeDelete, txManager),
                "older read statement snapshot does not see later own delete");

        MvccStatementSnapshot laterRead = txManager.beginStatement(tx);
        assertEquals(Optional.empty(), table.read(1, laterRead, txManager),
                "later statement snapshot observes the delete from statement 3");
    }

    @Test
    public void testStatementSequencesArePerTransaction() {
        MvccTransactionManager txManager = new MvccTransactionManager();

        MvccTransaction left = txManager.begin();
        MvccTransaction right = txManager.begin();

        assertEquals(MvccCommandSequence.of(1L), txManager.beginStatement(left).commandSequence(),
                "left transaction starts at command 1");
        assertEquals(MvccCommandSequence.of(2L), txManager.beginStatement(left).commandSequence(),
                "left transaction increments independently");
        assertEquals(MvccCommandSequence.of(1L), txManager.beginStatement(right).commandSequence(),
                "right transaction has its own command sequence");
    }
}
