package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordFlags;

/**
 * Page-backed MVCC table prototype used to move delos_mvcc away from Java-map
 * storage. It stores durable append-only version records in the A1 page file
 * and rebuilds a row directory on open.
 */
public final class PageBackedMvccTable implements AutoCloseable {
    private final PageBackedMvccTableStore store;
    private final MvccRowDirectory directory;

    private PageBackedMvccTable(PageBackedMvccTableStore store, MvccRowDirectory directory) {
        this.store = Objects.requireNonNull(store, "store");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public static PageBackedMvccTable open(Path path) throws IOException {
        PageBackedMvccTableStore store = PageBackedMvccTableStore.open(path);
        try {
            return new PageBackedMvccTable(store, MvccRowDirectory.fromStoredRecords(store.loadAll()));
        } catch (RuntimeException | IOException failure) {
            try {
                store.close();
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    public synchronized void insertCommitted(String key, String value, long transactionId, long commitSequence)
            throws IOException {
        requireCommittedSequence(commitSequence);
        if (read(key, new MvccCommitSequence(commitSequence)).isPresent()) {
            throw new IllegalStateException("row already visible at commit sequence " + commitSequence + ": " + key);
        }
        appendVersion(key, value, directory.nextRowId(), MvccVersionId.NONE, transactionId, 0L, commitSequence, 0);
    }

    public synchronized void insertUncommitted(String key, String value, long transactionId) throws IOException {
        appendVersion(key, value, directory.nextRowId(), MvccVersionId.NONE, transactionId, 0L, 0L, 0);
    }

    public synchronized void updateCommitted(String key, String value, long transactionId, long commitSequence)
            throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = directory.rowIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot update missing row: " + key));
        MvccVersionId previous = directory.newestVersionIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot update row without versions: " + key));
        appendVersion(key, value, rowId, previous, transactionId, 0L, commitSequence, 0);
    }

    public synchronized void deleteCommitted(String key, long transactionId, long commitSequence) throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = directory.rowIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot delete missing row: " + key));
        MvccVersionId previous = directory.newestVersionIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot delete row without versions: " + key));
        appendVersion(key, "", rowId, previous, transactionId, transactionId, commitSequence,
                MvccVersionRecordFlags.TOMBSTONE);
    }

    public synchronized Optional<String> read(String key, MvccCommitSequence snapshotSequence) {
        return directory.read(key, Objects.requireNonNull(snapshotSequence, "snapshotSequence"))
                .map(MvccRowPayload::valueAsUtf8);
    }

    public synchronized int physicalVersionCount(String key) {
        return directory.physicalVersionCount(key);
    }

    public synchronized int physicalVersionCount() {
        return directory.physicalVersionCount();
    }

    public synchronized int logicalRowCount() {
        return directory.logicalRowCount();
    }

    public synchronized long pageCount() throws IOException {
        return store.pageCount();
    }

    @Override
    public synchronized void close() throws IOException {
        store.close();
    }

    private void appendVersion(
            String key,
            String value,
            MvccRowId rowId,
            MvccVersionId previousVersionId,
            long transactionId,
            long deletedByTx,
            long commitSequence,
            int flags) throws IOException {
        MvccVersionId versionId = directory.nextVersionId();
        MvccRowPayload payload = MvccRowPayload.ofString(key, value);
        MvccVersionRecord record = new MvccVersionRecord(
                new MvccTupleHeader(
                        rowId,
                        versionId,
                        previousVersionId,
                        new MvccTransactionId(transactionId),
                        new MvccTransactionId(deletedByTx),
                        new MvccCommitSequence(commitSequence),
                        flags),
                MvccRowPayloadCodec.encode(payload));
        MvccVersionLocator locator = store.append(record);
        directory.addNewCommitted(key, rowId, new MvccRowDirectory.StoredVersion(locator, record, payload));
    }

    private static void requireCommittedSequence(long commitSequence) {
        if (commitSequence <= 0L) {
            throw new IllegalArgumentException("commit sequence must be positive for committed durable rows: "
                    + commitSequence);
        }
    }
}
