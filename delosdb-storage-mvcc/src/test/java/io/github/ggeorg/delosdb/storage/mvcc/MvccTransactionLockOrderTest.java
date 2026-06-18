package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;

/**
 * MVCC-18 lock-order proof for transaction metadata, table chains, and
 * provider-owned indexes.
 *
 * <p>The version-chain code consults {@link MvccTransactionCatalog} while
 * holding row/table monitors. This proof keeps concurrent commit, cleanup, and
 * provider-owned index lookup active at the same time so a future reverse lock
 * order fails as a bounded deadlock instead of silently entering the default
 * provider candidate path.</p>
 */
public final class MvccTransactionLockOrderTest {
    private static final int ROW_COUNT = 8;
    private static final int ITERATIONS = 64;

    @Test
    public void cleanupCommitAndIndexLookupDoNotDeadlock() throws Exception {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        DelosMvccTransactionCoordinator coordinator = (DelosMvccTransactionCoordinator) provider.transactionCoordinator();
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "lock_order");
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);

        TxContext seed = coordinator.begin();
        for (long key = 1L; key <= ROW_COUNT; key++) {
            table.insert(key, row(key, "seed-" + key), seed);
        }
        coordinator.commit(seed);

        TxContext build = coordinator.begin();
        VersionedIndex<Long, List<Object>> index = table.createIndex(
                new VersionedIndexMetadata(metadata, "idx_lock_order_name", "name", false),
                value -> value.get(1),
                build.currentView());
        coordinator.commit(build);

        @SuppressWarnings("unchecked")
        DelosMvccTable<Long, List<Object>> mvccTable = (DelosMvccTable<Long, List<Object>>) table;

        runConcurrent(List.of(
                writerWorker(table, coordinator),
                cleanupWorker(mvccTable, coordinator),
                indexLookupWorker(index, coordinator)));

        TxContext reader = coordinator.begin();
        assertEquals(ROW_COUNT, visibleRowCount(table.openScan(reader.currentView())));
        coordinator.abort(reader);
    }

    private static Callable<Void> writerWorker(
            VersionedTable<Long, List<Object>> table,
            DelosMvccTransactionCoordinator coordinator) {
        return () -> {
            for (int i = 0; i < ITERATIONS; i++) {
                long key = (i % ROW_COUNT) + 1L;
                TxContext writer = coordinator.begin();
                table.update(key, row(key, "writer-" + i), writer);
                coordinator.commit(writer);
                Thread.yield();
            }
            return null;
        };
    }

    private static Callable<Void> cleanupWorker(
            DelosMvccTable<Long, List<Object>> table,
            DelosMvccTransactionCoordinator coordinator) {
        return () -> {
            for (int i = 0; i < ITERATIONS; i++) {
                coordinator.cleanup(table);
                Thread.yield();
            }
            return null;
        };
    }

    private static Callable<Void> indexLookupWorker(
            VersionedIndex<Long, List<Object>> index,
            DelosMvccTransactionCoordinator coordinator) {
        return () -> {
            for (int i = 0; i < ITERATIONS; i++) {
                long key = (i % ROW_COUNT) + 1L;
                TxContext reader = coordinator.begin();
                try {
                    index.stats("seed-" + key, reader.currentView());
                    visibleRowCount(index.lookup("seed-" + key, reader.currentView()));
                    visibleRowCount(index.lookup("writer-" + i, reader.currentView()));
                } finally {
                    coordinator.abort(reader);
                }
                Thread.yield();
            }
            return null;
        };
    }

    private static void runConcurrent(List<Callable<Void>> workers) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers.size());
        CountDownLatch ready = new CountDownLatch(workers.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (Callable<Void> worker : workers) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "timed out waiting for lock-order start latch");
                    return worker.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "timed out waiting for lock-order workers to be ready");
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "lock-order executor did not stop cleanly");
        }
    }

    private static List<Object> row(long id, String value) {
        return List.of(id, value);
    }

    private static int visibleRowCount(VersionedScan<Long, List<Object>> scan) {
        int count = 0;
        try (scan) {
            while (scan.next()) {
                VersionedRow<Long, List<Object>> row = scan.row();
                if (row.value() != null) {
                    count++;
                }
            }
        }
        return count;
    }
}
