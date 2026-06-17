package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccWriteConflictException;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;

final class MvccConcurrentReadWriteTest {
    @TempDir
    Path tempDir;

    @Test
    void readersKeepStableSnapshotsWhileWriterCommits() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        int readerCount = 6;
        int readsPerReader = 200;

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);

            ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
            AtomicInteger stableReads = new AtomicInteger();

            List<Future<?>> futures = new ArrayList<>();
            for (int reader = 0; reader < readerCount; reader++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    for (int read = 0; read < readsPerReader; read++) {
                        String value = table.read("account:1", new MvccCommitSequence(1L)).orElseThrow();
                        if (!"alpha".equals(value)) {
                            failures.add(new AssertionError("snapshot 1 saw " + value));
                            return;
                        }
                        stableReads.incrementAndGet();
                    }
                }));
            }

            Future<MvccIndexTuple> writer = executor.submit(() -> {
                await(start);
                return table.updateCommitted("account:1", "beta", 2L, 2L);
            });

            start.countDown();
            writer.get(5, TimeUnit.SECONDS);
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            executor.shutdownNow();

            assertTrue(failures.isEmpty(), () -> "reader failures: " + failures);
            assertEquals(readerCount * readsPerReader, stableReads.get());
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertEquals("beta", table.read("account:1", new MvccCommitSequence(2L)).orElseThrow());
        }
    }

    @Test
    void sameRowWritersConflictFromSamePredecessorVersion() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            MvccVersionId predecessor = table.newestVersionIdForKey("account:1").orElseThrow();
            AtomicLong nextTx = new AtomicLong(2L);
            AtomicLong nextCommit = new AtomicLong(2L);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> writer = () -> {
                await(start);
                long tx = nextTx.getAndIncrement();
                long commit = nextCommit.getAndIncrement();
                try {
                    table.updateCommittedIfCurrentVersion(
                            "account:1", "writer-" + tx, predecessor, tx, commit);
                    return true;
                } catch (MvccWriteConflictException expected) {
                    return false;
                }
            };

            Future<Boolean> first = executor.submit(writer);
            Future<Boolean> second = executor.submit(writer);
            start.countDown();

            int successes = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            executor.shutdownNow();

            assertEquals(1, successes);
            assertEquals(2, table.physicalVersionCount("account:1"));
            assertTrue(table.read("account:1", new MvccCommitSequence(3L)).orElseThrow().startsWith("writer-"));
        }
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
