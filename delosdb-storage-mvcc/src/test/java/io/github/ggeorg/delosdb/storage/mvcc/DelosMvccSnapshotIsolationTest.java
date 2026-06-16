package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.List;
import java.util.Optional;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 9 snapshot-semantics proofs for the experimental MVCC provider.
 *
 * <p>The PostgreSQL-guided rule is: READ COMMITTED uses a fresh snapshot per
 * statement, while REPEATABLE READ keeps the transaction snapshot stable. The
 * provider exposes the primitive needed by the SQL bridge through
 * {@link VersionedTransactionCoordinator#refresh(TxContext)}.</p>
 */
public final class DelosMvccSnapshotIsolationTest {
    @Test
    public void testRefreshCapturesFreshReadCommittedStatementSnapshot() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTable<Long, List<Object>> table = provider.createTable(new VersionedTableMetadata("app", "snapshots"));
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        table.insert(1L, List.of(1, "alpha"), seed);
        coordinator.commit(seed);

        TxContext reader = coordinator.begin();
        assertEquals(Optional.of(List.of(1, "alpha")), table.read(1L, reader.currentView()));

        TxContext writer = coordinator.begin();
        table.update(1L, List.of(1, "beta"), writer);
        coordinator.commit(writer);

        assertEquals(Optional.of(List.of(1, "alpha")), table.read(1L, reader.currentView()),
                "the original transaction view remains repeatable-read stable");

        TxContext refreshedReader = coordinator.refresh(reader);
        assertEquals(Optional.of(List.of(1, "beta")), table.read(1L, refreshedReader.currentView()),
                "a refreshed statement view sees the later committed update");
        coordinator.abort(reader);
    }

    @Test
    public void testRefreshedSnapshotKeepsOwnWritesVisible() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTable<Long, List<Object>> table = provider.createTable(new VersionedTableMetadata("app", "own_writes"));
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext writerReader = coordinator.begin();
        table.insert(1L, List.of(1, "draft"), writerReader);

        TxContext refreshed = coordinator.refresh(writerReader);
        assertEquals(Optional.of(List.of(1, "draft")), table.read(1L, refreshed.currentView()),
                "statement refresh must not hide writes made by the same transaction");
        coordinator.abort(writerReader);

        TxContext afterAbort = coordinator.begin();
        assertEquals(Optional.empty(), table.read(1L, afterAbort.currentView()));
        coordinator.abort(afterAbort);
    }

    @Test
    public void testRefreshedSnapshotStillHidesActiveWriter() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTable<Long, List<Object>> table = provider.createTable(new VersionedTableMetadata("app", "active_writer"));
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        table.insert(1L, List.of(1, "alpha"), seed);
        coordinator.commit(seed);

        TxContext reader = coordinator.begin();
        TxContext writer = coordinator.begin();
        table.update(1L, List.of(1, "uncommitted"), writer);

        TxContext refreshed = coordinator.refresh(reader);
        assertEquals(Optional.of(List.of(1, "alpha")), table.read(1L, refreshed.currentView()),
                "fresh statement snapshot must still hide another active writer");

        coordinator.abort(writer);
        coordinator.abort(reader);
    }
}
