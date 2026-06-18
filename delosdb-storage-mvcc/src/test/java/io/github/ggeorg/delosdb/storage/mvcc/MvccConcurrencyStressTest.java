package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

/**
 * MVCC-9 deterministic concurrency stress proof.
 *
 * <p>This is deliberately not a random torture test. It holds one reader snapshot
 * stable while concurrent writers append independent versions, then verifies that
 * only newer snapshots see committed versions, aborted versions never leak, and
 * the provider-local recovery log reconstructs the same committed image after a
 * crash-style reopen.</p>
 */
public final class MvccConcurrencyStressTest {
    private static final int WRITER_COUNT = 6;

    @TempDir
    private Path storageDirectory;

    @Test
    public void stableReaderSnapshotSurvivesConcurrentCommittedUpdates() throws Exception {
        VersionedTableMetadata metadata = tableMetadata("concurrent_updates");
        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory.resolve("updates"));
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        for (long key = 1L; key <= WRITER_COUNT; key++) {
            table.insert(key, row(key, "before-" + key), seed);
        }
        coordinator.commit(seed);

        TxContext stableReader = coordinator.begin();
        assertEquals(expectedRows("before"), rows(table.openScan(stableReader.currentView())));

        runConcurrent(WRITER_COUNT, worker -> {
            long key = worker + 1L;
            TxContext writer = coordinator.begin();
            table.update(key, row(key, "after-" + key), writer);
            coordinator.commit(writer);
            return key;
        });

        assertEquals(expectedRows("before"), rows(table.openScan(stableReader.currentView())));
        coordinator.abort(stableReader);

        TxContext freshReader = coordinator.begin();
        assertEquals(expectedRows("after"), rows(table.openScan(freshReader.currentView())));
        coordinator.abort(freshReader);
    }

    @Test
    public void abortedVersionsNeverLeakUnderConcurrentInsertStress() throws Exception {
        VersionedTableMetadata metadata = tableMetadata("concurrent_abort_visibility");
        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory.resolve("aborts"));
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext stableReader = coordinator.begin();

        runConcurrent(WRITER_COUNT, worker -> {
            long key = worker + 1L;
            TxContext writer = coordinator.begin();
            String state = key % 2L == 0L ? "aborted" : "committed";
            table.insert(key, row(key, state + "-" + key), writer);
            if (key % 2L == 0L) {
                coordinator.abort(writer);
            } else {
                coordinator.commit(writer);
            }
            return key;
        });

        assertEquals(List.of(), rows(table.openScan(stableReader.currentView())));
        coordinator.abort(stableReader);

        TxContext freshReader = coordinator.begin();
        assertEquals(List.of(
                "1=[1, committed-1]",
                "3=[3, committed-3]",
                "5=[5, committed-5]"), rows(table.openScan(freshReader.currentView())));
        coordinator.abort(freshReader);
    }

    @Test
    public void recoveryAfterConcurrentStressPreservesOnlyCommittedRows() throws Exception {
        VersionedTableMetadata metadata = tableMetadata("concurrent_recovery");
        Path storage = storageDirectory.resolve("recovery");
        DelosMvccStorageProvider writerProvider = DelosMvccStorageProvider.open(storage);
        VersionedTable<Long, List<Object>> writerTable = writerProvider.createTable(metadata);
        VersionedTransactionCoordinator writerCoordinator = writerProvider.transactionCoordinator();

        TxContext seed = writerCoordinator.begin();
        for (long key = 1L; key <= WRITER_COUNT; key++) {
            writerTable.insert(key, row(key, "seed-" + key), seed);
        }
        writerCoordinator.commit(seed);

        runConcurrent(WRITER_COUNT, worker -> {
            long key = worker + 1L;
            TxContext writer = writerCoordinator.begin();
            if (key % 3L == 0L) {
                writerTable.update(key, row(key, "aborted-update-" + key), writer);
                writerCoordinator.abort(writer);
            } else {
                writerTable.update(key, row(key, "committed-update-" + key), writer);
                writerCoordinator.commit(writer);
            }
            return key;
        });

        TxContext beforeCrash = writerCoordinator.begin();
        assertEquals(List.of(
                "1=[1, committed-update-1]",
                "2=[2, committed-update-2]",
                "3=[3, seed-3]",
                "4=[4, committed-update-4]",
                "5=[5, committed-update-5]",
                "6=[6, seed-6]"), rows(writerTable.openScan(beforeCrash.currentView())));
        writerCoordinator.abort(beforeCrash);

        DelosMvccStorageProvider recoveredProvider = DelosMvccStorageProvider.open(storage);
        VersionedTable<Long, List<Object>> recoveredTable = recoveredProvider.openTable(metadata);
        VersionedTransactionCoordinator recoveredCoordinator = recoveredProvider.transactionCoordinator();
        TxContext recoveredReader = recoveredCoordinator.begin();
        assertEquals(List.of(
                "1=[1, committed-update-1]",
                "2=[2, committed-update-2]",
                "3=[3, seed-3]",
                "4=[4, committed-update-4]",
                "5=[5, committed-update-5]",
                "6=[6, seed-6]"), rows(recoveredTable.openScan(recoveredReader.currentView())));
        recoveredCoordinator.abort(recoveredReader);
    }

    private static List<Long> runConcurrent(int workerCount, Worker worker) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workerCount; i++) {
                final int workerId = i;
                futures.add(executor.submit(new Callable<Long>() {
                    @Override
                    public Long call() throws Exception {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS), "timed out waiting for stress start latch");
                        return worker.run(workerId);
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "timed out waiting for stress workers to be ready");
            start.countDown();

            List<Long> completed = new ArrayList<>();
            for (Future<Long> future : futures) {
                completed.add(future.get(10, TimeUnit.SECONDS));
            }
            Collections.sort(completed);
            return List.copyOf(completed);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "stress executor did not stop cleanly");
        }
    }

    private static VersionedTableMetadata tableMetadata(String tableName) {
        return new VersionedTableMetadata("app", tableName);
    }

    private static List<Object> row(long id, String value) {
        return List.of(id, value);
    }

    private static List<String> expectedRows(String valuePrefix) {
        List<String> rows = new ArrayList<>();
        for (long key = 1L; key <= WRITER_COUNT; key++) {
            rows.add(key + "=[" + key + ", " + valuePrefix + "-" + key + "]");
        }
        return List.copyOf(rows);
    }

    private static List<String> rows(VersionedScan<Long, List<Object>> scan) {
        List<String> rows = new ArrayList<>();
        try (scan) {
            while (scan.next()) {
                VersionedRow<Long, List<Object>> row = scan.row();
                rows.add(row.key() + "=" + row.value());
            }
        }
        Collections.sort(rows);
        return List.copyOf(rows);
    }

    private interface Worker {
        Long run(int workerId) throws Exception;
    }
}
