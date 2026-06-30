package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccWriteConflictException;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordFlags;

/**
 * Page-backed MVCC table prototype used to move delos_mvcc away from Java-map
 * storage. It stores durable version records in the A1 page file and rebuilds
 * a row directory on open. Vacuum can compact live records into existing pages
 * and mark emptied pages reusable for later writes.
 */
public final class PageBackedMvccTable implements AutoCloseable {
    private final PageBackedMvccTableStore store;
    private MvccRowDirectory directory;
    private final MvccPageMutationLog mutationLog;
    private final MvccTransactionOutcomeLog outcomeLog;
    private final MvccRowDirectoryStore rowDirectoryStore;

    private PageBackedMvccTable(
            PageBackedMvccTableStore store,
            MvccRowDirectory directory,
            MvccPageMutationLog mutationLog,
            MvccTransactionOutcomeLog outcomeLog,
            MvccRowDirectoryStore rowDirectoryStore) {
        this.store = Objects.requireNonNull(store, "store");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.mutationLog = mutationLog;
        this.outcomeLog = outcomeLog;
        this.rowDirectoryStore = Objects.requireNonNull(rowDirectoryStore, "rowDirectoryStore");
    }

    public static PageBackedMvccTable open(Path path) throws IOException {
        return open(path, null);
    }

    /**
     * Opens a page-backed table and, when a mutation log is supplied, applies
     * committed log records before rebuilding the row directory from pages.
     */
    public static PageBackedMvccTable open(Path path, Path mutationLogPath) throws IOException {
        return openInternal(path, mutationLogPath, null, false);
    }

    /**
     * Opens a page-backed table with an optional transaction outcome log. If the
     * outcome log already exists, recovery treats it as the authority for
     * deciding which page mutations materialize; otherwise the legacy mutation
     * log terminal markers are used once for compatibility and future writes
     * start maintaining the outcome log.
     */
    public static PageBackedMvccTable open(
            Path path,
            Path mutationLogPath,
            Path outcomeLogPath) throws IOException {
        boolean strictRecovery = outcomeLogPath != null && Files.exists(outcomeLogPath);
        return openInternal(path, mutationLogPath, outcomeLogPath, strictRecovery);
    }

    /** Opens a table and always requires transaction-outcome-log recovery. */
    public static PageBackedMvccTable openStrict(
            Path path,
            Path mutationLogPath,
            Path outcomeLogPath) throws IOException {
        Objects.requireNonNull(mutationLogPath, "mutationLogPath");
        Objects.requireNonNull(outcomeLogPath, "outcomeLogPath");
        return openInternal(path, mutationLogPath, outcomeLogPath, true);
    }

    private static PageBackedMvccTable openInternal(
            Path path,
            Path mutationLogPath,
            Path outcomeLogPath,
            boolean strictRecovery) throws IOException {
        PageBackedMvccTableStore store = PageBackedMvccTableStore.open(path);
        try {
            MvccPageMutationLog log = null;
            MvccTransactionOutcomeLog outcomes = outcomeLogPath == null
                    ? null
                    : MvccTransactionOutcomeLog.open(outcomeLogPath);
            if (mutationLogPath != null) {
                log = MvccPageMutationLog.open(mutationLogPath);
                MvccPageRecoveryRunner recovery = new MvccPageRecoveryRunner(log, store);
                if (strictRecovery) {
                    if (outcomes == null) {
                        throw new IllegalArgumentException("strict MVCC recovery requires a transaction outcome log");
                    }
                    recovery.recoverStrict(outcomes);
                } else {
                    recovery.recover();
                }
            }
            MvccRowDirectoryStore rowDirectory = MvccRowDirectoryStore.open(rowDirectoryPath(path));
            List<String> pageRecordErrors = store.pageRecordConsistencyErrors();
            if (!pageRecordErrors.isEmpty()) {
                new MvccDurableConsistencyCheck.Result(0, 0, 0, pageRecordErrors).assertValid();
            }
            MvccRowDirectory directory = MvccRowDirectory.fromStoredRecords(store.loadAll());
            reconcileRowDirectoryWithPages(rowDirectory, directory);
            MvccDurableConsistencyCheck.check(store, rowDirectory).assertValid();
            return new PageBackedMvccTable(store, directory, log, outcomes, rowDirectory);
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
        return insertCommitted(key, stringBytes(value), transactionId, commitSequence, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple insertCommitted(
            String key,
            String value,
            long transactionId,
            long commitSequence,
            DelosLogSequenceNumber pageLsn) throws IOException {
        return insertCommitted(key, stringBytes(value), transactionId, commitSequence, pageLsn);
    }

    public synchronized MvccIndexTuple insertCommitted(String key, byte[] value, long transactionId, long commitSequence)
            throws IOException {
        return insertCommitted(key, value, transactionId, commitSequence, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple insertCommitted(
            String key,
            byte[] value,
            long transactionId,
            long commitSequence,
            DelosLogSequenceNumber pageLsn) throws IOException {
        requireCommittedSequence(commitSequence);
        if (directory.rowIdForKey(key).isPresent()) {
            throw new MvccWriteConflictException("logical row already exists in durable page store: " + key);
        }
        return appendVersion(
                key, value, directory.nextRowId(), MvccVersionId.NONE, transactionId, 0L, commitSequence, 0, pageLsn);
    }

    public synchronized MvccIndexTuple insertUncommitted(String key, String value, long transactionId) throws IOException {
        return appendVersion(key, stringBytes(value), directory.nextRowId(), MvccVersionId.NONE, transactionId, 0L, 0L, 0);
    }

    public synchronized MvccIndexTuple updateCommitted(String key, String value, long transactionId, long commitSequence)
            throws IOException {
        return updateCommitted(key, stringBytes(value), transactionId, commitSequence, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple updateCommitted(
            String key,
            String value,
            long transactionId,
            long commitSequence,
            DelosLogSequenceNumber pageLsn) throws IOException {
        return updateCommitted(key, stringBytes(value), transactionId, commitSequence, pageLsn);
    }

    public synchronized MvccIndexTuple updateCommitted(String key, byte[] value, long transactionId, long commitSequence)
            throws IOException {
        return updateCommitted(key, value, transactionId, commitSequence, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple updateCommitted(
            String key,
            byte[] value,
            long transactionId,
            long commitSequence,
            DelosLogSequenceNumber pageLsn) throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = directory.rowIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot update missing row: " + key));
        MvccVersionId previous = directory.newestVersionIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot update row without versions: " + key));
        return appendVersion(key, value, rowId, previous, transactionId, 0L, commitSequence, 0, pageLsn);
    }

    /**
     * Appends an update only if the caller still owns the current newest row
     * version observed by its write snapshot. This is the durable-table
     * compare-and-append primitive used by the A8 concurrency proof: two same-row
     * writers racing from the same predecessor cannot both succeed.
     */
    public synchronized MvccIndexTuple updateCommittedIfCurrentVersion(
            String key,
            String value,
            MvccVersionId expectedCurrentVersionId,
            long transactionId,
            long commitSequence) throws IOException {
        return updateCommittedIfCurrentVersion(
                key, stringBytes(value), expectedCurrentVersionId, transactionId, commitSequence);
    }

    /**
     * Binary-payload overload for durable SQL row codecs.
     */
    public synchronized MvccIndexTuple updateCommittedIfCurrentVersion(
            String key,
            byte[] value,
            MvccVersionId expectedCurrentVersionId,
            long transactionId,
            long commitSequence) throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = rowIdForExistingKey(key, "update");
        MvccVersionId previous = requireExpectedCurrentVersion(key, expectedCurrentVersionId, "update");
        return appendVersion(
                key, value, rowId, previous, transactionId, 0L, commitSequence, 0, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple deleteCommitted(String key, long transactionId, long commitSequence) throws IOException {
        return deleteCommitted(key, transactionId, commitSequence, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple deleteCommitted(
            String key,
            long transactionId,
            long commitSequence,
            DelosLogSequenceNumber pageLsn) throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = directory.rowIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot delete missing row: " + key));
        MvccVersionId previous = directory.newestVersionIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot delete row without versions: " + key));
        return appendVersion(key, new byte[0], rowId, previous, transactionId, transactionId, commitSequence,
                MvccVersionRecordFlags.TOMBSTONE, pageLsn);
    }

    /**
     * Appends a tombstone only if the row still has the predecessor observed by
     * the deleting writer.
     */
    public synchronized MvccIndexTuple deleteCommittedIfCurrentVersion(
            String key,
            MvccVersionId expectedCurrentVersionId,
            long transactionId,
            long commitSequence) throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = rowIdForExistingKey(key, "delete");
        MvccVersionId previous = requireExpectedCurrentVersion(key, expectedCurrentVersionId, "delete");
        return appendVersion(key, new byte[0], rowId, previous, transactionId, transactionId, commitSequence,
                MvccVersionRecordFlags.TOMBSTONE, DelosLogSequenceNumber.NONE);
    }


    /**
     * Validates that a payload can be represented by the current MVCC durable
     * row-payload codec. Large rows may be persisted through overflow pages.
     */
    public static void requirePayloadCanBeEncoded(String key, byte[] value) {
        MvccRowPayload payload = new MvccRowPayload(key, value);
        MvccRowPayloadCodec.encode(payload);
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

    public synchronized Optional<MvccRowDirectoryStore.RowHeadRecord> rowDirectoryHeadForRowId(MvccRowId rowId) {
        try {
            return rowDirectoryStore.headForRowId(Objects.requireNonNull(rowId, "rowId"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not read durable MVCC row-directory head", e);
        }
    }

    public synchronized java.util.Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> durableRowDirectoryHeads() {
        try {
            return rowDirectoryStore.recoverHeads();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not recover durable MVCC row-directory heads", e);
        }
    }

    public synchronized MvccDurableConsistencyCheck.Result validateConsistency() {
        try {
            return MvccDurableConsistencyCheck.check(store, rowDirectoryStore);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not validate durable MVCC consistency", e);
        }
    }

    public synchronized Path rowDirectoryPath() {
        return rowDirectoryStore.path();
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

    public synchronized boolean hasVersion(MvccRowId rowId, MvccVersionId versionId) {
        return directory.containsVersion(rowId, versionId);
    }

    public synchronized MvccVacuumResult vacuum(MvccVacuumPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        MvccRowDirectory.VacuumSelection selection = directory.selectVacuum(plan.oldestVisibleThrough());
        if (selection.removedVersions() == 0) {
            return new MvccVacuumResult(
                    0,
                    0,
                    0,
                    directory.physicalVersionCount(),
                    directory.logicalRowCount());
        }
        if (mutationLog != null) {
            mutationLog.rewriteCheckpoint(selection.retainedRecords());
        }
        if (outcomeLog != null) {
            outcomeLog.rewriteCheckpoint(selection.retainedRecords());
        }
        directory = MvccRowDirectory.fromStoredRecords(store.rewrite(selection.retainedRecords()));
        rowDirectoryStore.rewriteHeads(directory.headRecords());
        return new MvccVacuumResult(
                selection.removedVersions(),
                0,
                selection.removedLogicalRows(),
                directory.physicalVersionCount(),
                directory.logicalRowCount());
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

    public synchronized long overflowPageCount() throws IOException {
        return store.overflowPageCount();
    }

    public synchronized long reusablePageCount() {
        return store.reusablePageCount();
    }

    public synchronized long pageCacheMaxPageCount() {
        return store.pageCacheMaxPageCount();
    }

    public synchronized long pageCacheSize() {
        return store.pageCacheSize();
    }

    public synchronized long pageCacheHitCount() {
        return store.pageCacheHitCount();
    }

    public synchronized long pageCacheMissCount() {
        return store.pageCacheMissCount();
    }

    public synchronized long pageCacheWriteCount() {
        return store.pageCacheWriteCount();
    }

    public synchronized long pageCacheEvictionCount() {
        return store.pageCacheEvictionCount();
    }

    public synchronized long pageCacheInvalidationCount() {
        return store.pageCacheInvalidationCount();
    }


    public synchronized PageBackedMvccTableStore.PageRecordStats pageRecordStats() {
        try {
            return store.pageRecordStats();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not collect MVCC page-record stats", e);
        }
    }

    public synchronized int pageRecordSlotCount() {
        return pageRecordStats().slotCount();
    }

    public synchronized int wrappedPageRecordCount() {
        return pageRecordStats().wrappedRecordCount();
    }

    public synchronized int legacyPageRecordCount() {
        return pageRecordStats().legacyRecordCount();
    }

    public synchronized int nonVersionPageRecordCount() {
        return pageRecordStats().nonVersionRecordCount();
    }

    public synchronized Path reusablePageIndexPath() {
        return store.reusablePageIndexPath();
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        try {
            store.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            rowDirectoryStore.close();
        } catch (Exception e) {
            if (failure == null) {
                failure = e instanceof IOException io ? io : new IOException(e);
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private MvccRowId rowIdForExistingKey(String key, String operation) {
        return directory.rowIdForKey(key)
                .orElseThrow(() -> new MvccWriteConflictException("cannot " + operation + " missing row: " + key));
    }

    private MvccVersionId requireExpectedCurrentVersion(
            String key,
            MvccVersionId expectedCurrentVersionId,
            String operation) {
        Objects.requireNonNull(expectedCurrentVersionId, "expectedCurrentVersionId");
        MvccVersionId current = directory.newestVersionIdForKey(key)
                .orElseThrow(() -> new MvccWriteConflictException(
                        "cannot " + operation + " row without versions: " + key));
        if (!current.equals(expectedCurrentVersionId)) {
            throw new MvccWriteConflictException("cannot " + operation + " row " + key
                    + " from stale version " + expectedCurrentVersionId + "; current version is " + current);
        }
        return current;
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
        return appendVersion(
                key,
                value,
                rowId,
                previousVersionId,
                transactionId,
                deletedByTx,
                commitSequence,
                flags,
                DelosLogSequenceNumber.NONE);
    }

    private MvccIndexTuple appendVersion(
            String key,
            byte[] value,
            MvccRowId rowId,
            MvccVersionId previousVersionId,
            long transactionId,
            long deletedByTx,
            long commitSequence,
            int flags,
            DelosLogSequenceNumber pageLsn) throws IOException {
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
        pageLsn = Objects.requireNonNull(pageLsn, "pageLsn");
        MvccVersionLocator locator = commitSequence > 0L
                ? appendCommittedRecord(transactionId, commitSequence, record, pageLsn)
                : store.append(record, pageLsn);
        directory.addNewCommitted(key, rowId, new MvccRowDirectory.StoredVersion(locator, record, payload));
        rowDirectoryStore.recordHead(new MvccRowDirectoryStore.RowHeadRecord(
                rowId,
                key,
                versionId,
                previousVersionId,
                locator,
                record.header().isTombstone()));
        return MvccIndexTuple.active(rowId, versionId, locator);
    }

    private MvccVersionLocator appendCommittedRecord(
            long transactionId,
            long commitSequence,
            MvccVersionRecord record,
            DelosLogSequenceNumber pageLsn) throws IOException {
        if (mutationLog != null) {
            mutationLog.appendVersion(transactionId, record);
        }
        if (outcomeLog != null) {
            outcomeLog.appendCommit(transactionId, commitSequence);
        }
        if (mutationLog != null) {
            mutationLog.appendCommit(transactionId, commitSequence);
        }
        return store.append(record, pageLsn);
    }

    private static void reconcileRowDirectoryWithPages(
            MvccRowDirectoryStore rowDirectoryStore,
            MvccRowDirectory pageDirectory) throws IOException {
        Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> pageHeads = pageDirectory.headRecords().stream()
                .collect(java.util.stream.Collectors.toMap(
                        MvccRowDirectoryStore.RowHeadRecord::rowId,
                        java.util.function.Function.identity(),
                        (left, right) -> right,
                        java.util.LinkedHashMap::new));
        Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> durableHeads = rowDirectoryStore.recoverHeads();
        if (!durableHeads.equals(pageHeads)) {
            rowDirectoryStore.rewriteHeads(pageHeads.values());
        }
    }

    public static Path rowDirectoryPath(Path pageFile) {
        Objects.requireNonNull(pageFile, "pageFile");
        return pageFile.resolveSibling(pageFile.getFileName() + ".rowdir");
    }

    public static Path overflowPath(Path pageFile) {
        return PageBackedMvccTableStore.overflowPath(pageFile);
    }

    public static Path reusablePageIndexPath(Path pageFile) {
        return PageBackedMvccTableStore.reusablePageIndexPath(pageFile);
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
