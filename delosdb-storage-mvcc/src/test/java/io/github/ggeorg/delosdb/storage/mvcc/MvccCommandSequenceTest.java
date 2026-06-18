package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Kernel proof for command/statement ordering inside one MVCC transaction.
 *
 * <p>This is the small PostgreSQL/H2-inspired command boundary before any SQL
 * statement wiring. A statement snapshot sees own writes from earlier commands,
 * but it must not see own writes or deletes from the current or later command.</p>
 */
public final class MvccCommandSequenceTest {
    @Test
    public void testReadSnapshotBetweenOwnUpdatesKeepsEarlierValue() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction tx = txManager.begin();
        MvccCommandSequence commandOne = MvccCommandSequence.of(1L);
        table.insert(1, "A", tx, commandOne);

        MvccCommandSequence commandTwo = MvccCommandSequence.of(2L);
        MvccSnapshot readBetweenWrites = txManager.snapshot(tx, commandTwo);
        assertEquals(Optional.of("A"), table.read(1, readBetweenWrites, txManager),
                "command 2 read snapshot sees command 1 write");

        MvccCommandSequence commandThree = MvccCommandSequence.of(3L);
        MvccSnapshot updateStatement = txManager.snapshot(tx, commandThree);
        table.update(1, "B", tx, updateStatement, txManager, commandThree);

        assertEquals(Optional.of("A"), table.read(1, readBetweenWrites, txManager),
                "old read snapshot keeps seeing command 1 value after command 3 update");

        MvccSnapshot laterRead = txManager.snapshot(tx, MvccCommandSequence.of(4L));
        assertEquals(Optional.of("B"), table.read(1, laterRead, txManager),
                "new read snapshot sees command 3 replacement value");
    }

    @Test
    public void testReadSnapshotBeforeOwnDeleteKeepsRowVisible() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction tx = txManager.begin();
        MvccCommandSequence commandOne = MvccCommandSequence.of(1L);
        table.insert(1, "live", tx, commandOne);

        MvccCommandSequence commandTwo = MvccCommandSequence.of(2L);
        MvccSnapshot readBeforeDelete = txManager.snapshot(tx, commandTwo);
        assertEquals(Optional.of("live"), table.read(1, readBeforeDelete, txManager),
                "command 2 read snapshot sees command 1 insert");

        MvccCommandSequence commandThree = MvccCommandSequence.of(3L);
        MvccSnapshot deleteStatement = txManager.snapshot(tx, commandThree);
        table.delete(1, tx, deleteStatement, txManager, commandThree);

        assertEquals(Optional.of("live"), table.read(1, readBeforeDelete, txManager),
                "old read snapshot keeps seeing row after command 3 delete");

        MvccSnapshot laterRead = txManager.snapshot(tx, MvccCommandSequence.of(4L));
        assertEquals(Optional.empty(), table.read(1, laterRead, txManager),
                "new read snapshot observes command 3 delete marker");
    }

    @Test
    public void testCurrentCommandSnapshotDoesNotSeeOwnInsertFromSameCommand() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction tx = txManager.begin();
        MvccCommandSequence commandOne = MvccCommandSequence.of(1L);
        MvccSnapshot statementOne = txManager.snapshot(tx, commandOne);

        table.insert(1, "same-command", tx, commandOne);

        assertEquals(Optional.empty(), table.read(1, statementOne, txManager),
                "a statement snapshot must not see a row inserted by that same command");
    }

    @Test
    public void testLegacyTransactionSnapshotStillSeesOwnWrites() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction tx = txManager.begin();
        table.insert(1, "legacy-visible", tx);
        table.update(1, "legacy-updated", tx, txManager.snapshot(tx), txManager);

        assertEquals(Optional.of("legacy-updated"), table.read(1, txManager.snapshot(tx), txManager),
                "legacy transaction-level snapshots keep existing proof behavior until SQL command wiring arrives");
    }
}
