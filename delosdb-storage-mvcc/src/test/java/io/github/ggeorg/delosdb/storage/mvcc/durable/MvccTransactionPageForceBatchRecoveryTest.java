package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactories;
import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;

/** Phase 7.4 crash proofs for one-force transaction page materialization. */
final class MvccTransactionPageForceBatchRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void transactionPageBatchUsesOneMainVolumeForce() throws Exception {
        Path tableFile = tempDir.resolve("one-force.dmvcc");
        CountingPageVolume volume = new CountingPageVolume(
                DelosPageVolumeFactories.fileChannel().open(tableFile));
        List<MvccVersionRecord> records = records(1L, 1L, 8, 128);

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tableFile, volume)) {
            List<MvccVersionLocator> locators = store.appendTransactionBatch(records, noneLsns(records.size()));
            assertEquals(records.size(), locators.size());
            assertEquals(1L, volume.forceCount(),
                    "all dirty main-table pages from one transaction must share one force boundary");
        }
    }

    @Test
    void recoveryCompletesTransactionAfterPartialBatchedPageWrites() throws Exception {
        Paths paths = paths("partial-write");
        List<MvccVersionRecord> records = records(2L, 2L, 3, 4_000);
        publishCommittedPayload(paths, 2L, 2L, records);

        CrashablePageVolume volume = CrashablePageVolume.failWritesFrom(
                DelosPageVolumeFactories.fileChannel().open(paths.table()), 2L);
        PageBackedMvccTableStore store = PageBackedMvccTableStore.open(paths.table(), volume);
        assertThrows(IOException.class,
                () -> store.appendTransactionBatch(records, noneLsns(records.size())));
        crash(store, volume);

        MvccPageRecoveryRunner.recoverStrict(paths.mutationLog(), paths.outcomeLog(), paths.table());
        assertCompleteTable(paths.table(), records.size());
    }

    @Test
    void recoveryCompletesTransactionAfterBatchedPageForceFailure() throws Exception {
        Paths paths = paths("force-failure");
        List<MvccVersionRecord> records = records(3L, 3L, 3, 4_000);
        publishCommittedPayload(paths, 3L, 3L, records);

        CrashablePageVolume volume = CrashablePageVolume.failForcesFrom(
                DelosPageVolumeFactories.fileChannel().open(paths.table()), 1L);
        PageBackedMvccTableStore store = PageBackedMvccTableStore.open(paths.table(), volume);
        assertThrows(IOException.class,
                () -> store.appendTransactionBatch(records, noneLsns(records.size())));
        crash(store, volume);

        MvccPageRecoveryRunner.recoverStrict(paths.mutationLog(), paths.outcomeLog(), paths.table());
        assertCompleteTable(paths.table(), records.size());
    }

    private static void publishCommittedPayload(
            Paths paths,
            long transactionId,
            long commitSequence,
            List<MvccVersionRecord> records) throws IOException {
        MvccPageMutationLog.open(paths.mutationLog())
                .appendPreparedTransaction(transactionId, commitSequence, records);
        MvccTransactionOutcomeLog.open(paths.outcomeLog()).appendCommit(transactionId, commitSequence);
    }

    private static void assertCompleteTable(Path tableFile, int expectedRows) throws Exception {
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            assertEquals(expectedRows, table.physicalVersionCount());
            assertEquals(expectedRows, table.logicalRowCount());
            table.validateConsistency().assertValid();
        }
    }

    private static void crash(PageBackedMvccTableStore store, CrashablePageVolume volume) throws IOException {
        assertThrows(IOException.class, store::close,
                "the simulated crash volume must prevent close from repairing the failed batch");
        volume.hardClose();
    }

    private Paths paths(String name) {
        return new Paths(
                tempDir.resolve(name + ".dmvcc"),
                tempDir.resolve(name + ".dmvcc.pagemut"),
                tempDir.resolve(name + ".dmvcc.txoutcome"));
    }

    private static List<MvccVersionRecord> records(
            long transactionId,
            long commitSequence,
            int count,
            int valueBytes) {
        List<MvccVersionRecord> records = new ArrayList<>(count);
        byte[] value = "v".repeat(valueBytes).getBytes(StandardCharsets.UTF_8);
        for (int index = 1; index <= count; index++) {
            records.add(new MvccVersionRecord(
                    new MvccTupleHeader(
                            new MvccRowId(index),
                            new MvccVersionId(index),
                            MvccVersionId.NONE,
                            new MvccTransactionId(transactionId),
                            MvccTransactionId.NONE,
                            new MvccCommitSequence(commitSequence),
                            0),
                    MvccRowPayloadCodec.encode(new MvccRowPayload("row:" + index, value))));
        }
        return List.copyOf(records);
    }

    private static List<DelosLogSequenceNumber> noneLsns(int count) {
        return java.util.Collections.nCopies(count, DelosLogSequenceNumber.NONE);
    }

    private record Paths(Path table, Path mutationLog, Path outcomeLog) {
    }

    private static final class CountingPageVolume implements DelosPageVolume {
        private final DelosPageVolume delegate;
        private long forceCount;

        private CountingPageVolume(DelosPageVolume delegate) {
            this.delegate = delegate;
        }

        @Override
        public DelosPage readPage(DelosPageId id) throws IOException {
            return delegate.readPage(id);
        }

        @Override
        public void writePage(DelosPage page) throws IOException {
            delegate.writePage(page);
        }

        @Override
        public DelosPage allocatePage(int pageType) throws IOException {
            return delegate.allocatePage(pageType);
        }

        @Override
        public long pageCount() throws IOException {
            return delegate.pageCount();
        }

        @Override
        public void force() throws IOException {
            delegate.force();
            forceCount++;
        }

        long forceCount() {
            return forceCount;
        }

        @Override
        public SyncPolicy syncPolicy() {
            return delegate.syncPolicy();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class CrashablePageVolume implements DelosPageVolume {
        private final DelosPageVolume delegate;
        private final long failWritesFrom;
        private final long failForcesFrom;
        private long writeCount;
        private long forceCount;

        private CrashablePageVolume(
                DelosPageVolume delegate,
                long failWritesFrom,
                long failForcesFrom) {
            this.delegate = delegate;
            this.failWritesFrom = failWritesFrom;
            this.failForcesFrom = failForcesFrom;
        }

        static CrashablePageVolume failWritesFrom(DelosPageVolume delegate, long writeNumber) {
            return new CrashablePageVolume(delegate, positive(writeNumber), Long.MAX_VALUE);
        }

        static CrashablePageVolume failForcesFrom(DelosPageVolume delegate, long forceNumber) {
            return new CrashablePageVolume(delegate, Long.MAX_VALUE, positive(forceNumber));
        }

        @Override
        public DelosPage readPage(DelosPageId id) throws IOException {
            return delegate.readPage(id);
        }

        @Override
        public void writePage(DelosPage page) throws IOException {
            long operation = ++writeCount;
            if (operation >= failWritesFrom) {
                throw new IOException("injected transaction page-batch write failure at write #" + operation);
            }
            delegate.writePage(page);
        }

        @Override
        public DelosPage allocatePage(int pageType) throws IOException {
            return delegate.allocatePage(pageType);
        }

        @Override
        public long pageCount() throws IOException {
            return delegate.pageCount();
        }

        @Override
        public void force() throws IOException {
            long operation = ++forceCount;
            if (operation >= failForcesFrom) {
                throw new IOException("injected transaction page-batch force failure at force #" + operation);
            }
            delegate.force();
        }

        @Override
        public SyncPolicy syncPolicy() {
            return delegate.syncPolicy();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        void hardClose() throws IOException {
            delegate.close();
        }

        private static long positive(long value) {
            if (value <= 0L) {
                throw new IllegalArgumentException("fault operation number must be positive: " + value);
            }
            return value;
        }
    }
}
