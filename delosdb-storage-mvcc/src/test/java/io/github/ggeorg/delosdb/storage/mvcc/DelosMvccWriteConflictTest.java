package io.github.ggeorg.delosdb.storage.mvcc;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedWriteConflictException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MVCC-10 write-conflict proof for the experimental MVCC provider.
 *
 * <p>The PostgreSQL-guided rule is: readers observe a consistent version and do
 * not block writers, but two writers cannot safely modify the same visible row
 * version at the same time. Rollback releases the write conflict; commit makes
 * the winning version authoritative for fresh snapshots and recovery.</p>
 */
public final class DelosMvccWriteConflictTest {
    @TempDir
    private Path storageDirectory;

    @Test
    public void testReaderDoesNotBlockActiveWriterAndFreshSnapshotSeesCommit() {
        Fixture fixture = seed("reader_writer");

        TxContext reader = fixture.coordinator().begin();
        assertEquals(Optional.of(List.of(1, "alpha")), fixture.table().read(1L, reader.currentView()));

        TxContext writer = fixture.coordinator().begin();
        fixture.table().update(1L, List.of(1, "beta"), writer);

        assertEquals(Optional.of(List.of(1, "alpha")), fixture.table().read(1L, reader.currentView()),
                "reader keeps its snapshot while another transaction writes");
        fixture.coordinator().commit(writer);

        assertEquals(Optional.of(List.of(1, "alpha")), fixture.table().read(1L, reader.currentView()),
                "old snapshot keeps the old version after writer commit");

        TxContext freshReader = fixture.coordinator().begin();
        assertEquals(Optional.of(List.of(1, "beta")), fixture.table().read(1L, freshReader.currentView()),
                "fresh snapshot sees the committed writer version");

        fixture.coordinator().abort(reader);
        fixture.coordinator().abort(freshReader);
    }

    @Test
    public void testActiveWriterBlocksSecondWriterForSameRowVersion() {
        Fixture fixture = seed("active_conflict");

        TxContext firstWriter = fixture.coordinator().begin();
        fixture.table().update(1L, List.of(1, "first"), firstWriter);

        TxContext secondWriter = fixture.coordinator().begin();
        VersionedWriteConflictException conflict = assertThrows(
                VersionedWriteConflictException.class,
                () -> fixture.table().update(1L, List.of(1, "second"), secondWriter));
        assertTrue(conflict.getMessage().contains("already deleted"));

        fixture.coordinator().abort(secondWriter);
        fixture.coordinator().abort(firstWriter);
    }

    @Test
    public void testRollbackReleasesWriteConflictForNextWriter() {
        Fixture fixture = seed("rollback_release");

        TxContext firstWriter = fixture.coordinator().begin();
        fixture.table().update(1L, List.of(1, "first"), firstWriter);
        fixture.coordinator().abort(firstWriter);

        TxContext secondWriter = fixture.coordinator().begin();
        fixture.table().update(1L, List.of(1, "second"), secondWriter);
        fixture.coordinator().commit(secondWriter);

        TxContext reader = fixture.coordinator().begin();
        assertEquals(Optional.of(List.of(1, "second")), fixture.table().read(1L, reader.currentView()));
        fixture.coordinator().abort(reader);
    }

    @Test
    public void testCommittedWriterBlocksStaleWriterOnOldSnapshot() {
        Fixture fixture = seed("stale_writer");

        TxContext staleWriter = fixture.coordinator().begin();
        TxContext firstWriter = fixture.coordinator().begin();
        fixture.table().update(1L, List.of(1, "committed"), firstWriter);
        fixture.coordinator().commit(firstWriter);

        assertEquals(Optional.of(List.of(1, "alpha")), fixture.table().read(1L, staleWriter.currentView()),
                "stale snapshot can still read the old version");
        assertThrows(VersionedWriteConflictException.class,
                () -> fixture.table().update(1L, List.of(1, "stale-write"), staleWriter),
                "stale writer must not overwrite a row version already deleted by a committed writer");

        fixture.coordinator().abort(staleWriter);
    }



    @Test
    public void testFirstCommitWinsSecondWriterConflictsAndRecoveryPreservesWinner() {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "first_commit_wins_recovery");
        Path storage = storageDirectory.resolve("first_commit_wins");
        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storage);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        table.insert(1L, List.of(1, "alpha"), seed);
        coordinator.commit(seed);

        TxContext stableReader = coordinator.begin();
        assertEquals(Optional.of(List.of(1, "alpha")), table.read(1L, stableReader.currentView()));

        TxContext winner = coordinator.begin();
        table.update(1L, List.of(1, "winner"), winner);

        TxContext loser = coordinator.begin();
        assertThrows(VersionedWriteConflictException.class,
                () -> table.update(1L, List.of(1, "loser"), loser),
                "a second writer must not overwrite the row version already claimed by the first writer");
        coordinator.abort(loser);

        coordinator.commit(winner);

        assertEquals(Optional.of(List.of(1, "alpha")), table.read(1L, stableReader.currentView()),
                "the reader snapshot remains stable while the winning writer commits");
        coordinator.abort(stableReader);

        TxContext freshReader = coordinator.begin();
        assertEquals(Optional.of(List.of(1, "winner")), table.read(1L, freshReader.currentView()),
                "fresh snapshots see only the committed winning version");
        coordinator.abort(freshReader);

        DelosMvccStorageProvider recovered = DelosMvccStorageProvider.open(storage);
        VersionedTable<Long, List<Object>> recoveredTable = recovered.openTable(metadata);
        VersionedTransactionCoordinator recoveredCoordinator = recovered.transactionCoordinator();
        TxContext recoveredReader = recoveredCoordinator.begin();
        assertEquals(Optional.of(List.of(1, "winner")), recoveredTable.read(1L, recoveredReader.currentView()),
                "recovery preserves the winning committed version and not the rejected writer");
        recoveredCoordinator.abort(recoveredReader);
    }

    @Test
    public void testActiveDeleteConflictsWithConcurrentUpdateAndRollbackReleasesIt() {
        Fixture fixture = seed("delete_conflict");

        TxContext deleter = fixture.coordinator().begin();
        fixture.table().delete(1L, deleter);

        TxContext updater = fixture.coordinator().begin();
        assertThrows(VersionedWriteConflictException.class,
                () -> fixture.table().update(1L, List.of(1, "blocked"), updater));
        fixture.coordinator().abort(updater);

        fixture.coordinator().abort(deleter);

        TxContext afterRollback = fixture.coordinator().begin();
        fixture.table().update(1L, List.of(1, "after-rollback"), afterRollback);
        fixture.coordinator().commit(afterRollback);

        TxContext reader = fixture.coordinator().begin();
        assertEquals(Optional.of(List.of(1, "after-rollback")), fixture.table().read(1L, reader.currentView()));
        fixture.coordinator().abort(reader);
    }

    private static Fixture seed(String tableName) {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTable<Long, List<Object>> table = provider.createTable(new VersionedTableMetadata("app", tableName));
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();
        TxContext seed = coordinator.begin();
        table.insert(1L, List.of(1, "alpha"), seed);
        coordinator.commit(seed);
        return new Fixture(table, coordinator);
    }

    private record Fixture(
            VersionedTable<Long, List<Object>> table,
            VersionedTransactionCoordinator coordinator) {
    }
}
