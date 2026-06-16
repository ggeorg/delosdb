package io.github.ggeorg.delosdb.storage.mvcc;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 11 recovery hardening tests for the experimental MVCC provider.
 *
 * <p>The provider-local log now behaves more like a WAL prefix: recovery is
 * idempotent for repeated terminal records, ignores an incomplete final record,
 * and can be compacted into a committed checkpoint image when no snapshots are
 * active.</p>
 */
public final class DelosMvccRecoveryHardeningTest {
    @TempDir
    private Path storageDirectory;

    @Test
    public void testRecoveryIsIdempotentForDuplicateCommitRecord() throws Exception {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "duplicate_commit");

        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext insert = transactions.begin();
        table.insert(1L, List.of(1, "alpha"), insert);
        transactions.commit(insert);

        Files.writeString(
                storageDirectory.resolve("delos-mvcc-storage.log"),
                "1\tCOMMIT\t1\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        DelosMvccStorageProvider recovered = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> recoveredTable = recovered.openTable(metadata);
        TxContext reader = recovered.transactionCoordinator().begin();
        assertEquals(Optional.of(List.of(1, "alpha")), recoveredTable.read(1L, reader.currentView()));
        recovered.transactionCoordinator().abort(reader);
    }

    @Test
    public void testRecoveryIgnoresIncompleteFinalRecord() throws Exception {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "torn_tail");

        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext insert = transactions.begin();
        table.insert(1L, List.of(1, "before-tail"), insert);
        transactions.commit(insert);

        Files.writeString(
                storageDirectory.resolve("delos-mvcc-storage.log"),
                "1\tINSERT\t999\t",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        DelosMvccStorageProvider recovered = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> recoveredTable = recovered.openTable(metadata);
        TxContext reader = recovered.transactionCoordinator().begin();
        assertEquals(Optional.of(List.of(1, "before-tail")), recoveredTable.read(1L, reader.currentView()));
        recovered.transactionCoordinator().abort(reader);
    }

    @Test
    public void testCheckpointRewritesLogToCommittedVisibleRowsOnly() {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "checkpoint_rows");

        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext insert = transactions.begin();
        table.insert(1L, List.of(1, "v1"), insert);
        table.insert(2L, List.of(2, "delete-me"), insert);
        transactions.commit(insert);

        TxContext update = transactions.begin();
        table.update(1L, List.of(1, "v2"), update);
        transactions.commit(update);

        TxContext delete = transactions.begin();
        table.delete(2L, delete);
        transactions.commit(delete);

        TxContext aborted = transactions.begin();
        table.insert(3L, List.of(3, "aborted"), aborted);
        transactions.abort(aborted);

        TxContext incomplete = transactions.begin();
        table.insert(4L, List.of(4, "incomplete"), incomplete);
        // left active intentionally until checkpoint refusal below
        assertThrows(IllegalStateException.class, provider::checkpoint);
        transactions.abort(incomplete);

        provider.cleanup();
        provider.checkpoint();

        DelosMvccStorageProvider recovered = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> recoveredTable = recovered.openTable(metadata);
        TxContext reader = recovered.transactionCoordinator().begin();
        assertEquals(Optional.of(List.of(1, "v2")), recoveredTable.read(1L, reader.currentView()));
        assertEquals(Optional.empty(), recoveredTable.read(2L, reader.currentView()));
        assertEquals(Optional.empty(), recoveredTable.read(3L, reader.currentView()));
        assertEquals(Optional.empty(), recoveredTable.read(4L, reader.currentView()));
        recovered.transactionCoordinator().abort(reader);
    }

    @Test
    public void testCheckpointPreservesEmptyTablesAfterCommittedDelete() {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "checkpoint_empty_table");

        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext insert = transactions.begin();
        table.insert(1L, List.of(1, "gone"), insert);
        transactions.commit(insert);

        TxContext delete = transactions.begin();
        table.delete(1L, delete);
        transactions.commit(delete);

        provider.cleanup();
        provider.checkpoint();

        DelosMvccStorageProvider recovered = DelosMvccStorageProvider.open(storageDirectory);
        assertEquals(List.of(metadata), recovered.listTables());
        VersionedTable<Long, List<Object>> recoveredTable = recovered.openTable(metadata);
        TxContext reader = recovered.transactionCoordinator().begin();
        assertEquals(Optional.empty(), recoveredTable.read(1L, reader.currentView()));
        recovered.transactionCoordinator().abort(reader);
    }
}
