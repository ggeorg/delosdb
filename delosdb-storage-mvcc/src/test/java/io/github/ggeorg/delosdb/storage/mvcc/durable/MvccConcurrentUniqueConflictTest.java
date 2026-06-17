package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccWriteConflictException;

final class MvccConcurrentUniqueConflictTest {
    @TempDir
    Path tempDir;

    @Test
    void concurrentDuplicateInsertAdmitsExactlyOneCommittedRow() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        int writerCount = 8;

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            ExecutorService executor = Executors.newFixedThreadPool(writerCount);
            CountDownLatch start = new CountDownLatch(1);
            AtomicLong nextTx = new AtomicLong(1L);
            AtomicLong nextCommit = new AtomicLong(1L);
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int writer = 0; writer < writerCount; writer++) {
                futures.add(executor.submit(insertAttempt(table, start, nextTx, nextCommit)));
            }

            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            executor.shutdownNow();

            assertEquals(1, successes);
            assertEquals(1, table.logicalRowCount());
            assertEquals(1, table.physicalVersionCount("account:1"));
            assertTrue(table.read("account:1", new MvccCommitSequence(Long.MAX_VALUE)).orElseThrow()
                    .startsWith("writer-"));
        }
    }

    private static Callable<Boolean> insertAttempt(
            PageBackedMvccTable table,
            CountDownLatch start,
            AtomicLong nextTx,
            AtomicLong nextCommit) {
        return () -> {
            await(start);
            long tx = nextTx.getAndIncrement();
            long commit = nextCommit.getAndIncrement();
            try {
                table.insertCommitted("account:1", "writer-" + tx, tx, commit);
                return true;
            } catch (MvccWriteConflictException expected) {
                return false;
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for start latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
