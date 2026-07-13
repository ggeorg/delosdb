package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proofs the non-durable lifecycle used by read-only MVCC scans. */
final class MvccReadOnlyTransactionLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void readOnlyBeginSnapshotAndAbortDoNotCreateStatusRecords() throws Exception {
        Path statusPath = directory.resolve("transactions.status");
        MvccTransactionManager manager =
                new MvccTransactionManager(MvccTransactionStatusStore.open(statusPath));

        MvccTransaction reader = manager.beginReadOnly();
        assertEquals(1, manager.activeTransactionCount());
        assertEquals(MvccCommitSequence.NONE, manager.snapshot(reader).visibleThrough());
        assertFalse(Files.exists(statusPath));

        manager.abort(reader);

        assertEquals(0, manager.activeTransactionCount());
        assertFalse(Files.exists(statusPath));
    }

    @Test
    void readOnlyIdsDoNotCreateGapsInDurableWriterRecovery() throws Exception {
        Path statusPath = directory.resolve("transactions.status");
        MvccTransactionManager manager =
                new MvccTransactionManager(MvccTransactionStatusStore.open(statusPath));

        MvccTransaction firstWriter = manager.begin();
        MvccTransaction reader = manager.beginReadOnly();
        long bytesAfterWriterBegin = Files.size(statusPath);
        manager.abort(reader);
        assertEquals(bytesAfterWriterBegin, Files.size(statusPath));
        manager.abort(firstWriter);

        MvccTransaction secondWriter = manager.begin();
        assertEquals(2L, secondWriter.id().value());
        manager.abort(secondWriter);

        MvccTransactionManager reopened =
                new MvccTransactionManager(MvccTransactionStatusStore.open(statusPath));
        assertEquals(MvccTransactionStatus.ABORTED, reopened.statusOf(firstWriter.id()));
        assertEquals(MvccTransactionStatus.ABORTED, reopened.statusOf(secondWriter.id()));
        MvccTransaction thirdWriter = reopened.begin();
        assertEquals(3L, thirdWriter.id().value());
        reopened.abort(thirdWriter);
    }

    @Test
    void readOnlyTransactionCannotCommit() {
        MvccTransactionManager manager = new MvccTransactionManager();
        MvccTransaction reader = manager.beginReadOnly();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> manager.commit(reader));

        assertTrue(failure.getMessage().contains("read-only"));
        assertEquals(1, manager.activeTransactionCount());
        manager.abort(reader);
    }
}
