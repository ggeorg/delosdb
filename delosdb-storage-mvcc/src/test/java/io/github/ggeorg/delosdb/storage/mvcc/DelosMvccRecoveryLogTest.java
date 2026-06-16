package io.github.ggeorg.delosdb.storage.mvcc;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageCapabilities;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 recovery-log tests for the experimental MVCC provider.
 *
 * <p>The log is provider-local and deliberately narrow. It proves the recovery
 * contract before DelosDB wires MVCC to Derby WAL/recovery.</p>
 */
public final class DelosMvccRecoveryLogTest {
    @TempDir
    private Path storageDirectory;

    @Test
    public void testCommittedRowsSurviveProviderReopenAndUncommittedRowsAreIgnored() throws Exception {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "recovery_account");

        DelosMvccStorageProvider writerProvider = DelosMvccStorageProvider.open(storageDirectory);
        assertTrue(writerProvider.capabilities().supports(VersionedStorageCapabilities.APPEND_ONLY_RECOVERY_LOG));
        VersionedTable<Long, List<Object>> writerTable = writerProvider.createTable(metadata);
        VersionedTransactionCoordinator writerTransactions = writerProvider.transactionCoordinator();

        TxContext committed = writerTransactions.begin();
        writerTable.insert(1L, List.of(1, "alpha"), committed);
        writerTransactions.commit(committed);

        TxContext aborted = writerTransactions.begin();
        writerTable.insert(2L, List.of(2, "aborted"), aborted);
        writerTransactions.abort(aborted);

        TxContext incomplete = writerTransactions.begin();
        writerTable.insert(3L, List.of(3, "incomplete"), incomplete);
        // Intentionally no commit/abort record: simulates a provider crash before completion.

        assertTrue(Files.exists(storageDirectory.resolve("delos-mvcc-storage.log")));

        DelosMvccStorageProvider recoveredProvider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> recoveredTable = recoveredProvider.openTable(metadata);
        TxContext reader = recoveredProvider.transactionCoordinator().begin();

        assertEquals(Optional.of(List.of(1, "alpha")), recoveredTable.read(1L, reader.currentView()));
        assertEquals(Optional.empty(), recoveredTable.read(2L, reader.currentView()));
        assertEquals(Optional.empty(), recoveredTable.read(3L, reader.currentView()));
        assertEquals(List.of("1=[1, alpha]"), rows(recoveredTable.openScan(reader.currentView())));
        recoveredProvider.transactionCoordinator().abort(reader);
    }

    @Test
    public void testCommittedUpdateAndDeleteReplayInCommitOrder() {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "recovery_items");

        DelosMvccStorageProvider firstOpen = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = firstOpen.createTable(metadata);
        VersionedTransactionCoordinator transactions = firstOpen.transactionCoordinator();

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

        DelosMvccStorageProvider recovered = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> recoveredTable = recovered.openTable(metadata);
        TxContext reader = recovered.transactionCoordinator().begin();

        assertEquals(Optional.of(List.of(1, "v2")), recoveredTable.read(1L, reader.currentView()));
        assertEquals(Optional.empty(), recoveredTable.read(2L, reader.currentView()));
        assertEquals(List.of("1=[1, v2]"), rows(recoveredTable.openScan(reader.currentView())));
        recovered.transactionCoordinator().abort(reader);
    }

    @Test
    public void testProviderReopenPreservesTableCatalog() {
        VersionedTableMetadata first = new VersionedTableMetadata("app", "first_recovered_table");
        VersionedTableMetadata second = new VersionedTableMetadata("app", "second_recovered_table");

        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        provider.createTable(first);
        provider.createTable(second);

        VersionedStorageProvider reopened = DelosMvccStorageProvider.open(storageDirectory);
        assertEquals(List.of(first, second), reopened.listTables());
    }

    private static List<String> rows(VersionedScan<Long, List<Object>> scan) {
        List<String> rows = new ArrayList<>();
        try (scan) {
            while (scan.next()) {
                rows.add(scan.row().key() + "=" + scan.row().value());
            }
        }
        return rows;
    }
}
