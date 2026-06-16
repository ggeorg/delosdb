package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.List;
import java.util.Optional;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedWriteConflictException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 12 write-conflict proofs for the experimental MVCC provider.
 *
 * <p>The PostgreSQL-guided rule is: readers observe a consistent version and do
 * not block writers, but two writers cannot safely modify the same visible row
 * version at the same time. Rollback releases the write conflict; commit makes
 * the newer version authoritative for fresh snapshots.</p>
 */
public final class DelosMvccWriteConflictTest {
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
