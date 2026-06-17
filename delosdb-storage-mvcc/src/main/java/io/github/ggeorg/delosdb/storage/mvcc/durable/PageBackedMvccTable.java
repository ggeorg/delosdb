package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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
    private final MvccPageMutationLog mutationLog;

    private PageBackedMvccTable(
            PageBackedMvccTableStore store,
            MvccRowDirectory directory,
            MvccPageMutationLog mutationLog) {
        this.store = Objects.requireNonNull(store, "store");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.mutationLog = mutationLog;
    }

    public static PageBackedMvccTable open(Path path) throws IOException {
        return open(path, null);
    }

    /**
     * Opens a page-backed table and, when a mutation log is supplied, applies
     * committed log records before rebuilding the row directory from pages.
     */
    public static PageBackedMvccTable open(Path path, Path mutationLogPath) throws IOException {
        PageBackedMvccTableStore store = PageBackedMvccTableStore.open(path);
        try {
            MvccPageMutationLog log = null;
            if (mutationLogPath != null) {
                log = MvccPageMutationLog.open(mutationLogPath);
                new MvccPageRecoveryRunner(log, store).recover();
            }
            return new PageBackedMvccTable(store, MvccRowDirectory.fromStoredRecords(store.loadAll()), log);
        } catch (RuntimeException | IOException failure) {
            try {
                store.close();
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    public synchronized MvccIndexTuple insertCommitted(String key, String value, long transactionId, long commitSequence)
            throws IOException {
        return insertCommitted(key, stringBytes(value), transactionId, commitSequence);
    }

    public synchronized MvccIndexTuple insertCommitted(String key, byte[] value, long transactionId, long commitSequence)
            throws IOException {
        requireCommittedSequence(commitSequence);
        if (readPayload(key, new MvccCommitSequence(commitSequence)).isPresent()) {
            throw new IllegalStateException("row already visible at commit sequence " + commitSequence + ": " + key);
        }
        return appendVersion(key, value, directory.nextRowId(), MvccVersionId.NONE, transactionId, 0L, commitSequence, 0);
    }

    public synchronized MvccIndexTuple insertUncommitted(String key, String value, long transactionId) throws IOException {
        return appendVersion(key, stringBytes(value), directory.nextRowId(), MvccVersionId.NONE, transactionId, 0L, 0L, 0);
    }

    public synchronized MvccIndexTuple updateCommitted(String key, String value, long transactionId, long commitSequence)
            throws IOException {
        return updateCommitted(key, stringBytes(value), transactionId, commitSequence);
    }

    public synchronized MvccIndexTuple updateCommitted(String key, byte[] value, long transactionId, long commitSequence)
            throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = directory.rowIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot update missing row: " + key));
        MvccVersionId previous = directory.newestVersionIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot update row without versions: " + key));
        return appendVersion(key, value, rowId, previous, transactionId, 0L, commitSequence, 0);
    }

    public synchronized MvccIndexTuple deleteCommitted(String key, long transactionId, long commitSequence) throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = directory.rowIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot delete missing row: " + key));
        MvccVersionId previous = directory.newestVersionIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot delete row without versions: " + key));
        return appendVersion(key, new byte[0], rowId, previous, transactionId, transactionId, commitSequence,
                MvccVersionRecordFlags.TOMBSTONE);
    }

    public synchronized Optional<String> read(String key, MvccCommitSequence snapshotSequence) {
        return readPayload(key, snapshotSequence).map(MvccRowPayload::valueAsUtf8);
    }

    public synchronized Optional<MvccRowPayload> readPayload(String key, MvccCommitSequence snapshotSequence) {
        return directory.read(key, Objects.requireNonNull(snapshotSequence, "snapshotSequence"));
    }

    public synchronized Optional<MvccRowId> rowIdForKey(String key) {
        return directory.rowIdForKey(key);
    }

    public synchronized Optional<MvccVersionId> newestVersionIdForKey(String key) {
        return directory.newestVersionIdForKey(key);
    }

    public synchronized Optional<MvccVersionLocator> newestVersionLocatorForKey(String key) {
        return directory.newestVersionLocatorForKey(key);
    }

    public synchronized java.util.List<MvccRowPayload> visibleRows(MvccCommitSequence snapshotSequence) {
        return directory.visiblePayloads(Objects.requireNonNull(snapshotSequence, "snapshotSequence"));
    }

    public synchronized Optional<MvccRowPayload> readVisibleIndexCandidate(
            MvccIndexTuple candidate,
            MvccCommitSequence snapshotSequence,
            Function<MvccRowPayload, Object> indexKeyExtractor,
            Object expectedIndexKey) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(snapshotSequence, "snapshotSequence");
        Objects.requireNonNull(indexKeyExtractor, "indexKeyExtractor");
        Optional<MvccRowPayload> visiblePayload = directory.readByRowId(candidate.rowId(), snapshotSequence);
        if (visiblePayload.isEmpty()) {
            return Optional.empty();
        }
        Object visibleIndexKey = indexKeyExtractor.apply(visiblePayload.get());
        return Objects.equals(expectedIndexKey, visibleIndexKey) ? visiblePayload : Optional.empty();
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

    private MvccIndexTuple appendVersion(
            String key,
            byte[] value,
            MvccRowId rowId,
            MvccVersionId previousVersionId,
            long transactionId,
            long deletedByTx,
            long commitSequence,
            int flags) throws IOException {
        MvccVersionId versionId = directory.nextVersionId();
        MvccRowPayload payload = new MvccRowPayload(key, value);
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
        MvccVersionLocator locator = commitSequence > 0L
                ? appendCommittedRecord(transactionId, commitSequence, record)
                : store.append(record);
        directory.addNewCommitted(key, rowId, new MvccRowDirectory.StoredVersion(locator, record, payload));
        return MvccIndexTuple.active(rowId, versionId, locator);
    }

    private MvccVersionLocator appendCommittedRecord(
            long transactionId,
            long commitSequence,
            MvccVersionRecord record) throws IOException {
        if (mutationLog != null) {
            mutationLog.appendVersion(transactionId, record);
            mutationLog.appendCommit(transactionId, commitSequence);
        }
        return store.append(record);
    }

    private static byte[] stringBytes(String value) {
        Objects.requireNonNull(value, "value");
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void requireCommittedSequence(long commitSequence) {
        if (commitSequence <= 0L) {
            throw new IllegalArgumentException("commit sequence must be positive for committed durable rows: "
                    + commitSequence);
        }
    }
}
