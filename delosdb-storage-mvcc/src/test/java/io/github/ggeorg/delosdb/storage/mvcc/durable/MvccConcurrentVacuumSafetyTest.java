package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;

final class MvccConcurrentVacuumSafetyTest {
    private static final Function<MvccRowPayload, Object> VALUE_INDEX_KEY = MvccRowPayload::valueAsUtf8;

    @TempDir
    Path tempDir;

    @Test
    void vacuumDoesNotRemoveVersionsNeededByActiveReaders() throws Exception {
        Path tableFile = tempDir.resolve("table.dmvcc");
        Path indexFile = tempDir.resolve("table_name.dmvcci");

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile);
                MvccIndexStore index = MvccIndexStore.open(indexFile)) {
            MvccIndexTuple alpha = table.insertCommitted("account:1", "alpha", 1L, 1L);
            index.appendCandidate("alpha", alpha);
            index.appendCandidate("beta", table.updateCommitted("account:1", "beta", 2L, 2L));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean readerDone = new AtomicBoolean(false);

            Future<?> reader = executor.submit(() -> {
                await(start);
                for (int read = 0; read < 200; read++) {
                    assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
                    assertEquals("alpha", table.readVisibleIndexCandidate(
                            index.lookupCandidates("alpha").get(0),
                            new MvccCommitSequence(1L),
                            VALUE_INDEX_KEY,
                            "alpha").orElseThrow().valueAsUtf8());
                }
                readerDone.set(true);
                return null;
            });

            Future<?> vacuum = executor.submit(() -> {
                await(start);
                for (int pass = 0; pass < 20; pass++) {
                    MvccVacuumResult result = MvccVacuum.vacuum(table, MvccVacuumPlan.through(1L), index);
                    assertEquals(0, result.removedVersions());
                    assertEquals(0, result.removedIndexCandidates());
                    assertEquals(2, result.remainingVersions());
                }
                return null;
            });

            start.countDown();
            reader.get(5, TimeUnit.SECONDS);
            vacuum.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertTrue(readerDone.get());
            assertEquals(1, index.candidateCount("alpha"));
            assertEquals(2, table.physicalVersionCount("account:1"));

            MvccVacuumResult afterReader = MvccVacuum.vacuum(table, MvccVacuumPlan.through(2L), index);
            assertEquals(1, afterReader.removedVersions());
            assertEquals(1, afterReader.removedIndexCandidates());
            assertEquals(0, index.candidateCount("alpha"));
            assertEquals(1, index.candidateCount("beta"));
            assertEquals("beta", table.read("account:1", new MvccCommitSequence(2L)).orElseThrow());
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
