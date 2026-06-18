package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

/**
 * MVCC-6 recovery proof for provider-owned durable index candidates.
 *
 * <p>Current delos_mvcc indexes are provider-owned candidate indexes rather than
 * independent durable B-trees. Recovery therefore rebuilds an index from the
 * recovered MVCC table image, then every lookup rechecks candidate visibility
 * against the authoritative version chain. This proof locks down that contract
 * before later vacuum/index-garbage work.</p>
 */
public final class MvccDurableIndexRecoveryTest {
    @TempDir
    private Path storageDirectory;

    @Test
    public void committedIndexedRowsSurviveProviderCrashAndRebuild() {
        VersionedTableMetadata metadata = tableMetadata("index_recovery_insert");
        DelosMvccStorageProvider writer = DelosMvccStorageProvider.open(storageDirectory.resolve("insert"));
        VersionedTable<Long, List<Object>> writerTable = writer.createTable(metadata);
        VersionedTransactionCoordinator writerCoordinator = writer.transactionCoordinator();

        TxContext insert = writerCoordinator.begin();
        writerTable.insert(1L, row(1, "alpha"), insert);
        writerTable.insert(2L, row(2, "beta"), insert);
        writerCoordinator.commit(insert);

        VersionedIndex<Long, List<Object>> writerIndex = buildNameIndex(
                writerTable,
                metadata,
                "idx_name_before_crash",
                writerCoordinator);
        TxContext beforeCrashReader = writerCoordinator.begin();
        assertEquals(List.of("1=[1, alpha]"), rows(writerIndex.lookup("alpha", beforeCrashReader.currentView())));
        writerCoordinator.abort(beforeCrashReader);

        RecoveredIndex recovered = recoverIndex(storageDirectory.resolve("insert"), metadata, "idx_name_after_recovery");
        TxContext reader = recovered.coordinator().begin();
        assertEquals(List.of("1=[1, alpha]"), rows(recovered.index().lookup("alpha", reader.currentView())));
        assertEquals(List.of("2=[2, beta]"), rows(recovered.index().lookup("beta", reader.currentView())));
        recovered.coordinator().abort(reader);
    }

    @Test
    public void committedDeleteReplaysBeforeRecoveredIndexBuild() {
        VersionedTableMetadata metadata = tableMetadata("index_recovery_delete");
        DelosMvccStorageProvider writer = DelosMvccStorageProvider.open(storageDirectory.resolve("delete"));
        VersionedTable<Long, List<Object>> writerTable = writer.createTable(metadata);
        VersionedTransactionCoordinator writerCoordinator = writer.transactionCoordinator();

        TxContext insert = writerCoordinator.begin();
        writerTable.insert(1L, row(1, "alpha"), insert);
        writerCoordinator.commit(insert);

        TxContext delete = writerCoordinator.begin();
        writerTable.delete(1L, delete);
        writerCoordinator.commit(delete);

        RecoveredIndex recovered = recoverIndex(storageDirectory.resolve("delete"), metadata, "idx_deleted_after_recovery");
        TxContext reader = recovered.coordinator().begin();
        assertEquals(List.of(), rows(recovered.index().lookup("alpha", reader.currentView())));
        assertEquals(List.of(), rows(recovered.table().openScan(reader.currentView())));
        recovered.coordinator().abort(reader);
    }

    @Test
    public void committedUpdateReplaysInOrderBeforeRecoveredIndexBuild() {
        VersionedTableMetadata metadata = tableMetadata("index_recovery_update");
        DelosMvccStorageProvider writer = DelosMvccStorageProvider.open(storageDirectory.resolve("update"));
        VersionedTable<Long, List<Object>> writerTable = writer.createTable(metadata);
        VersionedTransactionCoordinator writerCoordinator = writer.transactionCoordinator();

        TxContext insert = writerCoordinator.begin();
        writerTable.insert(1L, row(1, "alpha"), insert);
        writerCoordinator.commit(insert);

        TxContext update = writerCoordinator.begin();
        writerTable.update(1L, row(1, "beta"), update);
        writerCoordinator.commit(update);

        RecoveredIndex recovered = recoverIndex(storageDirectory.resolve("update"), metadata, "idx_updated_after_recovery");
        TxContext reader = recovered.coordinator().begin();
        assertEquals(List.of(), rows(recovered.index().lookup("alpha", reader.currentView())));
        assertEquals(List.of("1=[1, beta]"), rows(recovered.index().lookup("beta", reader.currentView())));
        recovered.coordinator().abort(reader);
    }

    @Test
    public void abortedAndIncompleteWritesDoNotRebuildIndexCandidates() {
        VersionedTableMetadata metadata = tableMetadata("index_recovery_abort");
        DelosMvccStorageProvider writer = DelosMvccStorageProvider.open(storageDirectory.resolve("abort"));
        VersionedTable<Long, List<Object>> writerTable = writer.createTable(metadata);
        VersionedTransactionCoordinator writerCoordinator = writer.transactionCoordinator();

        TxContext committed = writerCoordinator.begin();
        writerTable.insert(1L, row(1, "live"), committed);
        writerCoordinator.commit(committed);

        TxContext aborted = writerCoordinator.begin();
        writerTable.insert(2L, row(2, "aborted"), aborted);
        writerCoordinator.abort(aborted);

        TxContext incomplete = writerCoordinator.begin();
        writerTable.insert(3L, row(3, "incomplete"), incomplete);
        // No terminal record: simulates a crash before commit/abort.

        RecoveredIndex recovered = recoverIndex(storageDirectory.resolve("abort"), metadata, "idx_abort_after_recovery");
        TxContext reader = recovered.coordinator().begin();
        assertEquals(List.of("1=[1, live]"), rows(recovered.index().lookup("live", reader.currentView())));
        assertEquals(List.of(), rows(recovered.index().lookup("aborted", reader.currentView())));
        assertEquals(List.of(), rows(recovered.index().lookup("incomplete", reader.currentView())));
        recovered.coordinator().abort(reader);
    }

    private static RecoveredIndex recoverIndex(Path storageDirectory, VersionedTableMetadata metadata, String indexName) {
        DelosMvccStorageProvider recovered = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = recovered.openTable(metadata);
        VersionedIndex<Long, List<Object>> index = buildNameIndex(
                table,
                metadata,
                indexName,
                recovered.transactionCoordinator());
        return new RecoveredIndex(table, index, recovered.transactionCoordinator());
    }

    private static VersionedIndex<Long, List<Object>> buildNameIndex(
            VersionedTable<Long, List<Object>> table,
            VersionedTableMetadata metadata,
            String indexName,
            VersionedTransactionCoordinator coordinator) {
        TxContext build = coordinator.begin();
        VersionedIndex<Long, List<Object>> index = table.createIndex(
                new VersionedIndexMetadata(metadata, indexName, "name", false),
                row -> row.get(1),
                build.currentView());
        coordinator.commit(build);
        return index;
    }

    private static VersionedTableMetadata tableMetadata(String tableName) {
        return new VersionedTableMetadata("app", tableName);
    }

    private static List<Object> row(int id, String name) {
        return List.of(id, name);
    }

    private static List<String> rows(VersionedScan<Long, List<Object>> scan) {
        List<String> rows = new ArrayList<>();
        try (scan) {
            while (scan.next()) {
                VersionedRow<Long, List<Object>> row = scan.row();
                rows.add(row.key() + "=" + row.value());
            }
        }
        return rows;
    }

    private record RecoveredIndex(
            VersionedTable<Long, List<Object>> table,
            VersionedIndex<Long, List<Object>> index,
            VersionedTransactionCoordinator coordinator) {
    }
}
