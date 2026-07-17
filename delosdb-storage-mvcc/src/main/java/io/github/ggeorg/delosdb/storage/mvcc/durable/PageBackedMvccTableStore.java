package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactories;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactory;
import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccPageRecordCodec;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.failure.MvccStorageFailureHook;

/** Page-backed store for durable MVCC version records with vacuum-created page reuse. */
public final class PageBackedMvccTableStore implements AutoCloseable {
    private static final int SLOT_OVERHEAD_BYTES = 12;

    private static final DelosPageVolumeFactory FILE_VOLUME_FACTORY = DelosPageVolumeFactories.fileChannel();

    private final Path path;
    private final Path overflowPath;
    private final DelosPageVolumeFactory volumeFactory;
    private DelosPageVolume pageVolume;
    private MvccOverflowPayloadStore overflowStore;
    private final MvccReusablePageIndexStore reusablePageIndexStore;
    private final NavigableSet<Long> reusablePageIds;
    private final MvccFreeSpaceMapStore freeSpaceMapStore;
    private final NavigableMap<Long, Integer> freeBytesByPageId;
    private final MvccPageCache pageCache;
    private final MvccBufferFlushCoordinator bufferFlushCoordinator;
    private long freeSpaceMapLookupCount;
    private long freeSpaceMapHitCount;
    private long freeSpaceMapNonLastHitCount;
    private long freeSpaceMapMissCount;
    private long freeSpaceMapStaleEntryCount;
    private long freeSpaceMapUpdateCount;
    private long freeSpaceMapRebuildCount;
    private long pageMutationContextBeginCount;
    private long pageMutationContextCommitCount;
    private long pageMutationContextAbortCount;
    private long pageMutationContextPageReservationCount;
    private long pageMutationContextReservedBytes;
    private long pageMutationContextPageWriteCount;
    private long pageMutationContextFreeSpaceMapUpdateCount;
    private long pageMutationContextReusableIndexUpdateCount;
    private long attributeOverflowWriteCount;
    private long attributeOverflowReadCount;
    private long attributeOverflowInlineRowBytes;
    private long attributeOverflowValueBytes;
    private String lastPageMutationContextOperation = "none";
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private PageBackedMvccTableStore(
            Path path,
            DelosPageVolumeFactory volumeFactory,
            DelosPageVolume pageVolume,
            MvccOverflowPayloadStore overflowStore,
            MvccReusablePageIndexStore reusablePageIndexStore,
            NavigableSet<Long> reusablePageIds,
            MvccFreeSpaceMapStore freeSpaceMapStore,
            NavigableMap<Long, Integer> freeBytesByPageId,
            MvccPageCache pageCache,
            MvccBufferFlushCoordinator bufferFlushCoordinator) {
        this.path = Objects.requireNonNull(path, "path");
        this.overflowPath = overflowPath(path);
        this.volumeFactory = Objects.requireNonNull(volumeFactory, "volumeFactory");
        this.pageVolume = Objects.requireNonNull(pageVolume, "pageVolume");
        this.overflowStore = Objects.requireNonNull(overflowStore, "overflowStore");
        this.reusablePageIndexStore = Objects.requireNonNull(reusablePageIndexStore, "reusablePageIndexStore");
        this.reusablePageIds = Objects.requireNonNull(reusablePageIds, "reusablePageIds");
        this.freeSpaceMapStore = Objects.requireNonNull(freeSpaceMapStore, "freeSpaceMapStore");
        this.freeBytesByPageId = Objects.requireNonNull(freeBytesByPageId, "freeBytesByPageId");
        this.pageCache = Objects.requireNonNull(pageCache, "pageCache");
        this.bufferFlushCoordinator = Objects.requireNonNull(bufferFlushCoordinator, "bufferFlushCoordinator");
    }

    public static PageBackedMvccTableStore open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        DelosPageVolume pageVolume = FILE_VOLUME_FACTORY.open(path);
        MvccReusablePageIndexStore reusablePageIndexStore = MvccReusablePageIndexStore.open(
                reusablePageIndexPath(path));
        NavigableSet<Long> reusablePageIds = recoverReusablePageIds(pageVolume, reusablePageIndexStore);
        reusablePageIndexStore.rewrite(pageVolume.pageCount(), reusablePageIds);
        MvccFreeSpaceMapStore freeSpaceMapStore = MvccFreeSpaceMapStore.open(freeSpaceMapPath(path));
        NavigableMap<Long, Integer> freeBytesByPageId = recoverFreeSpaceMap(pageVolume, freeSpaceMapStore);
        freeSpaceMapStore.rewrite(pageVolume.pageCount(), freeBytesByPageId);
        PageBackedMvccTableStore store = new PageBackedMvccTableStore(
                path,
                FILE_VOLUME_FACTORY,
                pageVolume,
                MvccOverflowPayloadStore.open(overflowPath(path), FILE_VOLUME_FACTORY),
                reusablePageIndexStore,
                reusablePageIds,
                freeSpaceMapStore,
                freeBytesByPageId,
                new MvccPageCache(),
                new MvccBufferFlushCoordinator());
        store.freeSpaceMapRebuildCount++;
        return store;
    }

    static PageBackedMvccTableStore open(Path path, DelosPageVolume pageVolume) {
        try {
            MvccReusablePageIndexStore reusablePageIndexStore = MvccReusablePageIndexStore.open(
                    reusablePageIndexPath(path));
            NavigableSet<Long> reusablePageIds = recoverReusablePageIds(pageVolume, reusablePageIndexStore);
            reusablePageIndexStore.rewrite(pageVolume.pageCount(), reusablePageIds);
            MvccFreeSpaceMapStore freeSpaceMapStore = MvccFreeSpaceMapStore.open(freeSpaceMapPath(path));
            NavigableMap<Long, Integer> freeBytesByPageId = recoverFreeSpaceMap(pageVolume, freeSpaceMapStore);
            freeSpaceMapStore.rewrite(pageVolume.pageCount(), freeBytesByPageId);
            PageBackedMvccTableStore store = new PageBackedMvccTableStore(
                    path,
                    FILE_VOLUME_FACTORY,
                    pageVolume,
                    MvccOverflowPayloadStore.open(overflowPath(path), FILE_VOLUME_FACTORY),
                    reusablePageIndexStore,
                    reusablePageIds,
                    freeSpaceMapStore,
                    freeBytesByPageId,
                    new MvccPageCache(),
                new MvccBufferFlushCoordinator());
            store.freeSpaceMapRebuildCount++;
            return store;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open MVCC overflow payload store for " + path, e);
        }
    }

    public MvccVersionLocator append(MvccVersionRecord record) throws IOException {
        return append(record, DelosLogSequenceNumber.NONE);
    }

    public MvccVersionLocator append(
            MvccVersionRecord record,
            DelosLogSequenceNumber pageLsn) throws IOException {
        return writeLockedIo(() -> {
            try (MvccPageMutationContext context = beginPageMutationContext("append-version")) {
                MvccVersionLocator locator = appendUnlocked(
                        record,
                        pageLsn,
                        context,
                        false,
                        MvccStorageFailureHook.NOOP,
                        MvccStorageFailureHook.Context.transaction(1L, 1L, 1, 1));
                forceDirtyPages();
                context.commit();
                return locator;
            }
        });
    }

    /**
     * Appends all records from one committed transaction and forces the table
     * page volume once after every dirty page image has been written. The
     * free-space-map sidecar is also rewritten once after all page-capacity
     * updates have been staged.
     *
     * <p>The caller must make the covering WAL and transaction outcome durable
     * before entering this method. The page cache keeps each touched page dirty
     * until the complete write batch and its single force boundary succeed, so a
     * failed or interrupted batch is safe to replay from the transaction payload
     * log.</p>
     */
    List<MvccVersionLocator> appendTransactionBatch(
            List<MvccVersionRecord> records,
            List<DelosLogSequenceNumber> pageLsns) throws IOException {
        List<MvccVersionRecord> requiredRecords =
                List.copyOf(Objects.requireNonNull(records, "records"));
        MvccStorageFailureHook.Context context =
                MvccStorageFailureHook.Context.transaction(1L, 1L, 1, 1);
        return appendTransactionBatch(
                requiredRecords, pageLsns, MvccStorageFailureHook.NOOP, context);
    }

    List<MvccVersionLocator> appendTransactionBatch(
            List<MvccVersionRecord> records,
            List<DelosLogSequenceNumber> pageLsns,
            MvccStorageFailureHook failureHook,
            MvccStorageFailureHook.Context failureContext) throws IOException {
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        pageLsns = List.copyOf(Objects.requireNonNull(pageLsns, "pageLsns"));
        MvccStorageFailureHook requiredFailureHook =
                MvccStorageFailureHook.require(failureHook);
        MvccStorageFailureHook.Context requiredFailureContext =
                Objects.requireNonNull(failureContext, "failureContext");
        if (records.isEmpty()) {
            return List.of();
        }
        if (records.size() != pageLsns.size()) {
            throw new IllegalArgumentException("MVCC page batch record/LSN count mismatch: records="
                    + records.size() + ", pageLsns=" + pageLsns.size());
        }

        List<MvccVersionRecord> batchRecords = records;
        List<DelosLogSequenceNumber> batchLsns = pageLsns;
        return writeLockedIo(() -> {
            try (MvccPageMutationContext context = beginPageMutationContext("append-transaction-batch")) {
                List<MvccVersionLocator> locators = new ArrayList<>(batchRecords.size());
                for (int index = 0; index < batchRecords.size(); index++) {
                    locators.add(appendUnlocked(
                            batchRecords.get(index),
                            batchLsns.get(index),
                            context,
                            true,
                            requiredFailureHook,
                            requiredFailureContext));
                }
                persistFreeSpaceMap();
                forceDirtyPages();
                context.commit();
                return List.copyOf(locators);
            }
        });
    }

    private MvccVersionLocator appendUnlocked(
            MvccVersionRecord record,
            DelosLogSequenceNumber pageLsn,
            MvccPageMutationContext context,
            boolean deferFreeSpaceMapPersistence,
            MvccStorageFailureHook failureHook,
            MvccStorageFailureHook.Context failureContext) throws IOException {
        Objects.requireNonNull(record, "record");
        pageLsn = Objects.requireNonNull(pageLsn, "pageLsn");
        Objects.requireNonNull(context, "context");
        EncodedVersion encodedVersion = encodeForPageRecord(
                record, failureHook, failureContext);
        byte[] encoded = encodedVersion.bytes();

        int requiredBytes = Math.addExact(encoded.length, SLOT_OVERHEAD_BYTES);
        bufferFlushCoordinator.recordLogForcedThrough(pageLsn);
        context.reservePageCapacity(requiredBytes);
        DelosPage page = writablePage(
                encoded.length,
                deferFreeSpaceMapPersistence,
                failureHook,
                failureContext);
        int slotId = page.appendRecord(encoded);
        DelosPage writtenPage = page.withPageLsn(pageLsn.value());
        writePage(writtenPage, context);
        updateFreeSpaceMap(writtenPage, context, !deferFreeSpaceMapPersistence);
        return new MvccVersionLocator(page.pageId(), slotId);
    }

    public List<StoredVersionRecord> loadAll() throws IOException {
        return readLockedIo(this::loadAllUnlocked);
    }

    private List<StoredVersionRecord> loadAllUnlocked() throws IOException {
        List<StoredVersionRecord> records = new ArrayList<>();
        for (MvccDurablePageScan.SlotRecord slot : scanPages().slotRecords()) {
            records.add(readStoredVersionRecord(
                    slot.locator(),
                    MvccPageRecordCodec.decode(slot.payload()).versionRecord()));
        }
        return records;
    }

    public List<StoredVersionRecord> rewrite(List<MvccVersionRecord> records) throws IOException {
        return writeLockedIo(() -> {
            Objects.requireNonNull(records, "records");
            Path rewritePath = path.resolveSibling(path.getFileName() + ".rewrite");
            Path rewriteOverflowPath = overflowPath(rewritePath);
            Files.deleteIfExists(rewritePath);
            Files.deleteIfExists(rewriteOverflowPath);
            try (PageBackedMvccTableStore rewrite = new PageBackedMvccTableStore(
                    rewritePath,
                    volumeFactory,
                    volumeFactory.open(rewritePath),
                    MvccOverflowPayloadStore.open(rewriteOverflowPath, volumeFactory),
                    MvccReusablePageIndexStore.open(reusablePageIndexPath(rewritePath)),
                    new TreeSet<>(),
                    MvccFreeSpaceMapStore.open(freeSpaceMapPath(rewritePath)),
                    new TreeMap<>(),
                    new MvccPageCache(),
                new MvccBufferFlushCoordinator())) {
                for (MvccVersionRecord record : records) {
                    rewrite.append(record);
                }
            }

            installRewritePages(rewritePath);
            overflowStore.close();
            Files.deleteIfExists(overflowPath);
            if (Files.exists(rewriteOverflowPath)) {
                Files.move(rewriteOverflowPath, overflowPath, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(rewritePath);
            MvccReusablePageIndexStore.open(reusablePageIndexPath(rewritePath)).delete();
            MvccFreeSpaceMapStore.open(freeSpaceMapPath(rewritePath)).delete();
            overflowStore = MvccOverflowPayloadStore.open(overflowPath, volumeFactory);
            rebuildReusablePageIds();
            rebuildFreeSpaceMap();
            return loadAllUnlocked();
        });
    }

    List<StoredVersionRecord> rewritePage(long pageId, List<MvccVersionRecord> retainedPageRecords) throws IOException {
        return writeLockedIo(() -> {
            Objects.requireNonNull(retainedPageRecords, "retainedPageRecords");
            long pageCount = pageVolume.pageCount();
            if (pageId < 0L || pageId >= pageCount) {
                throw new IllegalArgumentException("page id out of range for page-local MVCC prune: "
                        + pageId + ", pageCount=" + pageCount);
            }
            try (MvccPageMutationContext context = beginPageMutationContext("rewrite-page")) {
                DelosPage page = DelosPage.empty(new DelosPageId(pageId), DelosPage.DATA_PAGE_TYPE);
                for (MvccVersionRecord record : retainedPageRecords) {
                    byte[] encoded = encodeAndRequireSinglePageRecord(record);
                    int requiredBytes = Math.addExact(encoded.length, SLOT_OVERHEAD_BYTES);
                    context.reservePageCapacity(requiredBytes);
                    if (page.freeBytes() < requiredBytes) {
                        throw new IllegalStateException("retained MVCC records no longer fit on page " + pageId
                                + " during page-local prune; required=" + requiredBytes
                                + ", free=" + page.freeBytes());
                    }
                    page.appendRecord(encoded);
                }
                writePage(page, context);
                if (page.slotCount() == 0) {
                    reusablePageIds.add(pageId);
                } else {
                    reusablePageIds.remove(pageId);
                }
                persistReusablePageIndex(context);
                updateFreeSpaceMap(page, context);
                forceDirtyPages();
                context.commit();
                return loadAllUnlocked();
            }
        });
    }

    public long pageCount() throws IOException {
        return readLockedIo(() -> pageVolume.pageCount());
    }

    public long overflowPageCount() throws IOException {
        return readLockedIo(() -> overflowStore.pageCount());
    }

    public long reusablePageCount() {
        return readLockedUnchecked(() -> (long) reusablePageIds.size());
    }

    public Path freeSpaceMapPath() {
        return readLockedUnchecked(() -> freeSpaceMapStore.path());
    }

    public long freeSpaceMapPageCount() {
        return readLockedUnchecked(() -> (long) freeBytesByPageId.size());
    }

    public int freeSpaceMapMaxFreeBytes() {
        return readLockedUnchecked(() -> freeBytesByPageId.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0));
    }

    public long freeSpaceMapLookupCount() {
        return readLockedUnchecked(() -> freeSpaceMapLookupCount);
    }

    public long freeSpaceMapHitCount() {
        return readLockedUnchecked(() -> freeSpaceMapHitCount);
    }

    public long freeSpaceMapNonLastHitCount() {
        return readLockedUnchecked(() -> freeSpaceMapNonLastHitCount);
    }

    public long freeSpaceMapMissCount() {
        return readLockedUnchecked(() -> freeSpaceMapMissCount);
    }

    public long freeSpaceMapStaleEntryCount() {
        return readLockedUnchecked(() -> freeSpaceMapStaleEntryCount);
    }

    public long freeSpaceMapUpdateCount() {
        return readLockedUnchecked(() -> freeSpaceMapUpdateCount);
    }

    public long freeSpaceMapRebuildCount() {
        return readLockedUnchecked(() -> freeSpaceMapRebuildCount);
    }

    public List<String> freeSpaceMapPageSummaries() {
        return readLockedUnchecked(() -> freeBytesByPageId.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .toList());
    }

    public long pageMutationContextBeginCount() {
        return readLockedUnchecked(() -> pageMutationContextBeginCount);
    }

    public long pageMutationContextCommitCount() {
        return readLockedUnchecked(() -> pageMutationContextCommitCount);
    }

    public long pageMutationContextAbortCount() {
        return readLockedUnchecked(() -> pageMutationContextAbortCount);
    }

    public long pageMutationContextPageReservationCount() {
        return readLockedUnchecked(() -> pageMutationContextPageReservationCount);
    }

    public long pageMutationContextReservedBytes() {
        return readLockedUnchecked(() -> pageMutationContextReservedBytes);
    }

    public long pageMutationContextPageWriteCount() {
        return readLockedUnchecked(() -> pageMutationContextPageWriteCount);
    }

    public long pageMutationContextFreeSpaceMapUpdateCount() {
        return readLockedUnchecked(() -> pageMutationContextFreeSpaceMapUpdateCount);
    }

    public long pageMutationContextReusableIndexUpdateCount() {
        return readLockedUnchecked(() -> pageMutationContextReusableIndexUpdateCount);
    }

    public String lastPageMutationContextOperation() {
        return readLockedUnchecked(() -> lastPageMutationContextOperation);
    }

    public long pageCacheMaxPageCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().maxPages());
    }

    public long pageCacheSize() {
        return readLockedUnchecked(() -> pageCache.snapshot().size());
    }

    public long pageCacheHitCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().hits());
    }

    public long pageCacheMissCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().misses());
    }

    public long pageCacheWriteCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().writes());
    }

    public long pageCacheEvictionCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().evictions());
    }

    public long pageCacheInvalidationCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().invalidations());
    }

    public long pageCachePinCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().pins());
    }

    public long pageCacheUnpinCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().unpins());
    }

    public long pageCachePinnedPageCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().pinnedPages());
    }

    public long pageCacheDirtyPageCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().dirtyPages());
    }

    public long pageCacheFlushListPageCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().flushListPages());
    }

    public long pageCacheFlushCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().flushes());
    }

    public long pageCachePinnedEvictionSkipCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().pinnedEvictionSkips());
    }


    public long pageCacheGroupedForceBatchCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().groupedForceBatches());
    }

    public long pageCacheGroupedForcedPageCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().groupedForcedPages());
    }

    public long pageCacheWalBeforeFlushCheckCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().walBeforeFlushChecks());
    }

    public long pageCacheWalBeforeFlushFailureCount() {
        return readLockedUnchecked(() -> pageCache.snapshot().walBeforeFlushFailures());
    }

    public long pageCacheLastPageGeneration() {
        return readLockedUnchecked(() -> pageCache.snapshot().lastPageGeneration());
    }

    public long attributeOverflowWriteCount() {
        return readLockedUnchecked(() -> attributeOverflowWriteCount);
    }

    public long attributeOverflowReadCount() {
        return readLockedUnchecked(() -> attributeOverflowReadCount);
    }

    public long attributeOverflowInlineRowBytes() {
        return readLockedUnchecked(() -> attributeOverflowInlineRowBytes);
    }

    public long attributeOverflowValueBytes() {
        return readLockedUnchecked(() -> attributeOverflowValueBytes);
    }

    List<String> pageRecordConsistencyErrors() throws IOException {
        return readLockedIo(() -> {
            List<String> errors = new ArrayList<>();
            for (MvccDurablePageScan.SlotRecord slot : scanPages().slotRecords()) {
                try {
                    MvccPageRecordCodec.decodeVersionRecord(slot.payload());
                } catch (RuntimeException invalidRecord) {
                    errors.add("page " + slot.pageId().value() + " slot " + slot.slotId()
                            + " has invalid MVCC page-record header/body: "
                            + invalidRecord.getMessage());
                }
            }
            return List.copyOf(errors);
        });
    }

    PageRecordStats pageRecordStats() throws IOException {
        return readLockedIo(() -> {
            MvccDurablePageScan scan = scanPages();
            int slotCount = 0;
            int wrappedRecordCount = 0;
            int legacyRecordCount = 0;
            int versionRecordCount = 0;
            int nonVersionRecordCount = 0;
            for (MvccDurablePageScan.SlotRecord slot : scan.slotRecords()) {
                slotCount++;
                MvccPageRecordCodec.PageRecordMetadata metadata = MvccPageRecordCodec.metadata(slot.payload());
                if (metadata.legacyFormat()) {
                    legacyRecordCount++;
                } else {
                    wrappedRecordCount++;
                }
                if (metadata.versionRecord()) {
                    versionRecordCount++;
                } else {
                    nonVersionRecordCount++;
                }
            }
            return new PageRecordStats(
                    scan.pageCount(),
                    slotCount,
                    wrappedRecordCount,
                    legacyRecordCount,
                    versionRecordCount,
                    nonVersionRecordCount);
        });
    }

    List<String> reusablePageConsistencyErrors() throws IOException {
        return readLockedIo(() -> {
            List<String> errors = new ArrayList<>();
            long pageCount = pageVolume.pageCount();
            NavigableSet<Long> emptyPages = scanPages().emptyPageIds();
            for (Long pageNumber : reusablePageIds) {
                if (pageNumber == null) {
                    errors.add("reusable-page index contains null page id");
                    continue;
                }
                if (pageNumber < 0L || pageNumber >= pageCount) {
                    errors.add("reusable-page index contains out-of-range page "
                            + pageNumber + " for pageCount=" + pageCount);
                    continue;
                }
                if (!emptyPages.contains(pageNumber)) {
                    errors.add("reusable-page index marks non-empty page " + pageNumber + " reusable");
                }
            }
            for (Long emptyPage : emptyPages) {
                if (!reusablePageIds.contains(emptyPage)) {
                    errors.add("empty MVCC page " + emptyPage + " is missing from reusable-page index");
                }
            }
            return List.copyOf(errors);
        });
    }

    List<String> freeSpaceMapConsistencyErrors() throws IOException {
        return readLockedIo(() -> {
            List<String> errors = new ArrayList<>();
            MvccDurablePageScan scan = scanPages();
            long pageCount = scan.pageCount();
            for (var entry : freeBytesByPageId.entrySet()) {
                Long pageNumber = entry.getKey();
                Integer freeBytes = entry.getValue();
                if (pageNumber == null) {
                    errors.add("free-space map contains null page id");
                    continue;
                }
                if (pageNumber < 0L || pageNumber >= pageCount) {
                    errors.add("free-space map contains out-of-range page "
                            + pageNumber + " for pageCount=" + pageCount);
                    continue;
                }
                Integer actualFreeBytes = scan.freeBytesByPageId().get(pageNumber);
                if (!Objects.equals(freeBytes, actualFreeBytes)) {
                    errors.add("free-space map page " + pageNumber + " has freeBytes="
                            + freeBytes + " but page image has freeBytes=" + actualFreeBytes);
                }
            }
            for (var entry : scan.freeBytesByPageId().entrySet()) {
                if (!Objects.equals(freeBytesByPageId.get(entry.getKey()), entry.getValue())) {
                    errors.add("page " + entry.getKey() + " is missing or stale in free-space map");
                }
            }
            return List.copyOf(errors);
        });
    }

    public Path reusablePageIndexPath() {
        return readLockedUnchecked(() -> reusablePageIndexStore.path());
    }

    @Override
    public void close() throws IOException {
        writeLockedIo(() -> {
            IOException failure = null;
            try {
                pageCache.flushAll(pageVolume, bufferFlushCoordinator);
                pageCache.clear();
                pageVolume.close();
            } catch (IOException e) {
                failure = e;
            }
            try {
                overflowStore.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        });
    }


    static byte[] encodeAndRequireSinglePageRecord(MvccVersionRecord record) {
        byte[] encoded = MvccPageRecordCodec.encodeVersionRecord(Objects.requireNonNull(record, "record"));
        int maxRecordBytes = maxSingleRecordBytes();
        if (encoded.length > maxRecordBytes) {
            throw new IllegalArgumentException("MVCC page record is too large for one page: "
                    + encoded.length + " bytes; max=" + maxRecordBytes);
        }
        return encoded;
    }

    static Path overflowPath(Path pageFile) {
        Objects.requireNonNull(pageFile, "pageFile");
        return pageFile.resolveSibling(pageFile.getFileName() + ".overflow");
    }

    static Path reusablePageIndexPath(Path pageFile) {
        Objects.requireNonNull(pageFile, "pageFile");
        return pageFile.resolveSibling(pageFile.getFileName() + ".free");
    }

    static Path freeSpaceMapPath(Path pageFile) {
        Objects.requireNonNull(pageFile, "pageFile");
        return pageFile.resolveSibling(pageFile.getFileName() + ".fsm");
    }

    private EncodedVersion encodeForPageRecord(
            MvccVersionRecord record,
            MvccStorageFailureHook failureHook,
            MvccStorageFailureHook.Context failureContext) throws IOException {
        byte[] encoded = MvccPageRecordCodec.encodeVersionRecord(Objects.requireNonNull(record, "record"));
        if (encoded.length <= maxSingleRecordBytes()) {
            return new EncodedVersion(record, encoded);
        }

        MvccRowPayload payload = MvccRowPayloadCodec.decode(record.payload());
        if (canStoreValueAsAttributeOverflow(record, payload)) {
            failureHook.hit(
                    MvccStorageFailureHook.Point.DURING_OVERFLOW_PUBLICATION,
                    failureContext);
            MvccOverflowPayloadDescriptor descriptor = overflowStore.write(payload.value());
            byte[] attributeReferencePayload = MvccAttributeOverflowRowPayloadCodec.encode(
                    payload.key(),
                    payload.value().length,
                    descriptor);
            MvccVersionRecord attributeReferenceRecord = new MvccVersionRecord(record.header(), attributeReferencePayload);
            byte[] attributeReferenceBytes = encodeAndRequireSinglePageRecord(attributeReferenceRecord);
            attributeOverflowWriteCount++;
            attributeOverflowInlineRowBytes += attributeReferencePayload.length;
            attributeOverflowValueBytes += payload.value().length;
            return new EncodedVersion(attributeReferenceRecord, attributeReferenceBytes);
        }

        failureHook.hit(
                MvccStorageFailureHook.Point.DURING_OVERFLOW_PUBLICATION,
                failureContext);
        MvccOverflowPayloadDescriptor descriptor = overflowStore.write(record.payload());
        byte[] referencePayload = MvccOverflowPayloadReferenceCodec.encode(
                new MvccOverflowPayloadReferenceCodec.Reference(payload.key(), descriptor));
        MvccVersionRecord referenceRecord = new MvccVersionRecord(record.header(), referencePayload);
        byte[] referenceBytes = encodeAndRequireSinglePageRecord(referenceRecord);
        return new EncodedVersion(referenceRecord, referenceBytes);
    }

    private static boolean canStoreValueAsAttributeOverflow(MvccVersionRecord record, MvccRowPayload payload) {
        if (payload.value().length == 0) {
            return false;
        }
        int attributePayloadLength = MvccAttributeOverflowRowPayloadCodec.encodedLengthForKey(payload.key());
        MvccVersionRecord attributeReferenceRecord = new MvccVersionRecord(
                record.header(),
                new byte[attributePayloadLength]);
        return MvccPageRecordCodec.encodedLength(attributeReferenceRecord) <= maxSingleRecordBytes();
    }

    private StoredVersionRecord readStoredVersionRecord(
            MvccVersionLocator locator,
            MvccVersionRecord record) throws IOException {
        byte[] payload = record.payload();
        if (MvccAttributeOverflowRowPayloadCodec.isAttributeOverflowPayload(payload)) {
            MvccAttributeOverflowRowPayloadCodec.Reference reference =
                    MvccAttributeOverflowRowPayloadCodec.decode(payload);
            byte[] valueBytes = overflowStore.read(reference.descriptor());
            if (valueBytes.length != reference.valueLength()) {
                throw new IllegalStateException("MVCC attribute-overflow value length mismatch for key "
                        + reference.key() + ": expected " + reference.valueLength()
                        + ", found " + valueBytes.length);
            }
            MvccRowPayload rowPayload = new MvccRowPayload(reference.key(), valueBytes);
            attributeOverflowReadCount++;
            return new StoredVersionRecord(locator, new MvccVersionRecord(record.header(), MvccRowPayloadCodec.encode(rowPayload)));
        }
        if (!MvccOverflowPayloadReferenceCodec.isOverflowReference(payload)) {
            return new StoredVersionRecord(locator, record);
        }
        MvccOverflowPayloadReferenceCodec.Reference reference = MvccOverflowPayloadReferenceCodec.decode(payload);
        byte[] rowPayloadBytes = overflowStore.read(reference.descriptor());
        MvccRowPayload rowPayload = MvccRowPayloadCodec.decode(rowPayloadBytes);
        if (!reference.key().equals(rowPayload.key())) {
            throw new IllegalStateException("MVCC overflow reference key " + reference.key()
                    + " does not match row-payload key " + rowPayload.key());
        }
        return new StoredVersionRecord(locator, new MvccVersionRecord(record.header(), rowPayloadBytes));
    }

    static boolean requiresOverflowPayload(MvccVersionRecord record) {
        Objects.requireNonNull(record, "record");
        return MvccPageRecordCodec.encodeVersionRecord(record).length > maxSingleRecordBytes();
    }

    private static int maxSingleRecordBytes() {
        return DelosPage.empty(new DelosPageId(0L)).freeBytes() - SLOT_OVERHEAD_BYTES;
    }

    private DelosPage writablePage(
            int encodedRecordLength,
            boolean deferFreeSpaceMapPersistence,
            MvccStorageFailureHook failureHook,
            MvccStorageFailureHook.Context failureContext) throws IOException {
        int requiredBytes = Math.addExact(encodedRecordLength, SLOT_OVERHEAD_BYTES);
        DelosPage mapped = pageFromFreeSpaceMap(
                requiredBytes,
                deferFreeSpaceMapPersistence,
                failureHook,
                failureContext);
        if (mapped != null) {
            return mapped;
        }
        DelosPage reusable = takeReusablePage(
                requiredBytes, failureHook, failureContext);
        if (reusable != null) {
            return reusable;
        }
        long count = pageVolume.pageCount();
        if (count == 0) {
            return allocatePage(
                    DelosPage.DATA_PAGE_TYPE, failureHook, failureContext);
        }
        DelosPage last = readPage(new DelosPageId(count - 1L));
        if (last.freeBytes() >= requiredBytes) {
            return last;
        }
        return allocatePage(
                DelosPage.DATA_PAGE_TYPE, failureHook, failureContext);
    }

    private DelosPage pageFromFreeSpaceMap(
            int requiredBytes,
            boolean deferFreeSpaceMapPersistence,
            MvccStorageFailureHook failureHook,
            MvccStorageFailureHook.Context failureContext) throws IOException {
        freeSpaceMapLookupCount++;
        boolean changed = false;
        long pageCount = pageVolume.pageCount();
        java.util.Iterator<java.util.Map.Entry<Long, Integer>> entries = freeBytesByPageId.entrySet().iterator();
        while (entries.hasNext()) {
            java.util.Map.Entry<Long, Integer> entry = entries.next();
            long pageNumber = entry.getKey();
            int indexedFreeBytes = entry.getValue();
            if (pageNumber < 0L || pageNumber >= pageCount) {
                entries.remove();
                freeSpaceMapStaleEntryCount++;
                changed = true;
                continue;
            }
            if (indexedFreeBytes < requiredBytes) {
                continue;
            }
            DelosPage page = readPage(new DelosPageId(pageNumber));
            if (page.freeBytes() >= requiredBytes) {
                freeSpaceMapHitCount++;
                if (pageNumber != pageCount - 1L) {
                    freeSpaceMapNonLastHitCount++;
                }
                if (reusablePageIds.contains(pageNumber)) {
                    failureHook.hit(
                            MvccStorageFailureHook.Point.DURING_VACUUM_REUSE,
                            failureContext);
                    reusablePageIds.remove(pageNumber);
                    persistReusablePageIndex();
                }
                if (indexedFreeBytes != page.freeBytes()) {
                    entry.setValue(page.freeBytes());
                    changed = true;
                }
                if (changed && !deferFreeSpaceMapPersistence) {
                    persistFreeSpaceMap();
                }
                return page;
            }
            entry.setValue(page.freeBytes());
            freeSpaceMapStaleEntryCount++;
            changed = true;
        }
        freeSpaceMapMissCount++;
        if (changed && !deferFreeSpaceMapPersistence) {
            persistFreeSpaceMap();
        }
        return null;
    }

    private DelosPage takeReusablePage(
            int requiredBytes,
            MvccStorageFailureHook failureHook,
            MvccStorageFailureHook.Context failureContext) throws IOException {
        boolean changed = false;
        java.util.Iterator<Long> ids = reusablePageIds.iterator();
        while (ids.hasNext()) {
            long pageNumber = ids.next();
            DelosPage page = readPage(new DelosPageId(pageNumber));
            if (page.slotCount() == 0 && page.freeBytes() >= requiredBytes) {
                failureHook.hit(
                        MvccStorageFailureHook.Point.DURING_VACUUM_REUSE,
                        failureContext);
                ids.remove();
                persistReusablePageIndex();
                return page;
            }
            ids.remove();
            changed = true;
        }
        if (changed) {
            persistReusablePageIndex();
        }
        return null;
    }

    private void installRewritePages(Path rewritePath) throws IOException {
        long retainedPageCount;
        try (DelosPageVolume rewriteVolume = volumeFactory.open(rewritePath)) {
            retainedPageCount = rewriteVolume.pageCount();
            ensurePageCapacity(retainedPageCount);
            for (long pageNumber = 0L; pageNumber < retainedPageCount; pageNumber++) {
                writePage(rewriteVolume.readPage(new DelosPageId(pageNumber)));
            }
        }

        long pageCount = pageVolume.pageCount();
        for (long pageNumber = retainedPageCount; pageNumber < pageCount; pageNumber++) {
            writePage(DelosPage.empty(new DelosPageId(pageNumber), DelosPage.DATA_PAGE_TYPE));
        }
        forceDirtyPages();
    }

    private void ensurePageCapacity(long requiredPageCount) throws IOException {
        while (pageVolume.pageCount() < requiredPageCount) {
            allocatePage(DelosPage.DATA_PAGE_TYPE);
        }
    }

    private void rebuildReusablePageIds() throws IOException {
        reusablePageIds.clear();
        reusablePageIds.addAll(recoverReusablePageIds(pageVolume, reusablePageIndexStore));
        persistReusablePageIndex();
    }

    private void persistReusablePageIndex() throws IOException {
        persistReusablePageIndex(null);
    }

    private void persistReusablePageIndex(MvccPageMutationContext context) throws IOException {
        reusablePageIndexStore.rewrite(pageVolume.pageCount(), reusablePageIds);
        if (context != null) {
            context.recordReusablePageIndexUpdate();
        }
    }

    private void updateFreeSpaceMap(DelosPage page) throws IOException {
        updateFreeSpaceMap(page, null);
    }

    private void updateFreeSpaceMap(DelosPage page, MvccPageMutationContext context) throws IOException {
        updateFreeSpaceMap(page, context, true);
    }

    private void updateFreeSpaceMap(
            DelosPage page,
            MvccPageMutationContext context,
            boolean persistImmediately) throws IOException {
        freeBytesByPageId.put(page.pageId().value(), page.freeBytes());
        freeSpaceMapUpdateCount++;
        if (context != null) {
            context.recordFreeSpaceMapUpdate();
        }
        if (persistImmediately) {
            persistFreeSpaceMap();
        }
    }

    private void rebuildFreeSpaceMap() throws IOException {
        freeBytesByPageId.clear();
        freeBytesByPageId.putAll(recoverFreeSpaceMap(pageVolume, freeSpaceMapStore));
        freeSpaceMapRebuildCount++;
        persistFreeSpaceMap();
    }

    private void persistFreeSpaceMap() throws IOException {
        freeSpaceMapStore.rewrite(pageVolume.pageCount(), freeBytesByPageId);
    }

    private static NavigableSet<Long> recoverReusablePageIds(
            DelosPageVolume pageVolume,
            MvccReusablePageIndexStore reusablePageIndexStore) throws IOException {
        Objects.requireNonNull(pageVolume, "pageVolume");
        Objects.requireNonNull(reusablePageIndexStore, "reusablePageIndexStore");
        NavigableSet<Long> emptyPages = scanPages(pageVolume).emptyPageIds();
        if (!reusablePageIndexStore.exists()) {
            return emptyPages;
        }
        MvccReusablePageIndexStore.Snapshot indexed = readReusablePageIndexIfValid(reusablePageIndexStore);
        NavigableSet<Long> reconciled = new TreeSet<>();
        long pageCount = pageVolume.pageCount();
        if (indexed != null && indexed.pageCount() == pageCount) {
            for (Long pageNumber : indexed.reusablePageIds()) {
                if (emptyPages.contains(pageNumber)) {
                    reconciled.add(pageNumber);
                }
            }
        }
        reconciled.addAll(emptyPages);
        return reconciled;
    }

    private static MvccReusablePageIndexStore.Snapshot readReusablePageIndexIfValid(
            MvccReusablePageIndexStore reusablePageIndexStore) throws IOException {
        try {
            return reusablePageIndexStore.read();
        } catch (IllegalStateException invalidIndex) {
            return null;
        }
    }

    private static NavigableMap<Long, Integer> recoverFreeSpaceMap(
            DelosPageVolume pageVolume,
            MvccFreeSpaceMapStore freeSpaceMapStore) throws IOException {
        Objects.requireNonNull(pageVolume, "pageVolume");
        Objects.requireNonNull(freeSpaceMapStore, "freeSpaceMapStore");
        NavigableMap<Long, Integer> scanned = scanPages(pageVolume).freeBytesByPageId();
        if (!freeSpaceMapStore.exists()) {
            return scanned;
        }
        MvccFreeSpaceMapStore.Snapshot indexed = readFreeSpaceMapIfValid(freeSpaceMapStore);
        if (indexed == null || indexed.pageCount() != pageVolume.pageCount()) {
            return scanned;
        }
        NavigableMap<Long, Integer> reconciled = new TreeMap<>();
        for (var entry : indexed.freeBytesByPageId().entrySet()) {
            Integer actualFreeBytes = scanned.get(entry.getKey());
            if (Objects.equals(actualFreeBytes, entry.getValue())) {
                reconciled.put(entry.getKey(), entry.getValue());
            }
        }
        reconciled.putAll(scanned);
        return reconciled;
    }

    private static MvccFreeSpaceMapStore.Snapshot readFreeSpaceMapIfValid(
            MvccFreeSpaceMapStore freeSpaceMapStore) throws IOException {
        try {
            return freeSpaceMapStore.read();
        } catch (IllegalStateException invalidMap) {
            return null;
        }
    }

    MvccPageMutationContext beginPageMutationContext(String operation) {
        return new MvccPageMutationContext(this, operation);
    }

    void recordMutationContextBegin(String operation) {
        pageMutationContextBeginCount++;
        lastPageMutationContextOperation = operation;
    }

    void recordMutationContextCommit(String operation) {
        pageMutationContextCommitCount++;
        lastPageMutationContextOperation = operation;
    }

    void recordMutationContextAbort(String operation) {
        pageMutationContextAbortCount++;
        lastPageMutationContextOperation = operation;
    }

    void recordMutationContextPageReservation(int bytes) {
        pageMutationContextPageReservationCount++;
        pageMutationContextReservedBytes += bytes;
    }

    void recordMutationContextPageWrite() {
        pageMutationContextPageWriteCount++;
    }

    void recordMutationContextFreeSpaceMapUpdate() {
        pageMutationContextFreeSpaceMapUpdateCount++;
    }

    void recordMutationContextReusableIndexUpdate() {
        pageMutationContextReusableIndexUpdateCount++;
    }

    private DelosPage readPage(DelosPageId pageId) throws IOException {
        return pageCache.read(pageVolume, pageId);
    }

    private void writePage(DelosPage page) throws IOException {
        writePage(page, null);
    }

    private void writePage(DelosPage page, MvccPageMutationContext context) throws IOException {
        pageCache.putDirty(page);
        if (context != null) {
            context.recordPageWrite();
        }
    }

    private void forceDirtyPages() throws IOException {
        pageCache.flushAll(pageVolume, bufferFlushCoordinator);
    }

    private DelosPage allocatePage(int pageType) throws IOException {
        return allocatePage(
                pageType,
                MvccStorageFailureHook.NOOP,
                MvccStorageFailureHook.Context.transaction(1L, 1L, 1, 1));
    }

    private DelosPage allocatePage(
            int pageType,
            MvccStorageFailureHook failureHook,
            MvccStorageFailureHook.Context failureContext) throws IOException {
        failureHook.hit(
                MvccStorageFailureHook.Point.DURING_PAGE_ALLOCATION,
                failureContext);
        DelosPage page = pageVolume.allocatePage(pageType);
        pageCache.putClean(page);
        return page;
    }

    private MvccDurablePageScan scanPages() throws IOException {
        return MvccDurablePageScan.scan(new MvccDurablePageScan.PageSource() {
            @Override
            public long pageCount() throws IOException {
                return pageVolume.pageCount();
            }

            @Override
            public DelosPage readPage(DelosPageId pageId) throws IOException {
                return PageBackedMvccTableStore.this.readPage(pageId);
            }
        });
    }

    private static MvccDurablePageScan scanPages(DelosPageVolume pageVolume) throws IOException {
        Objects.requireNonNull(pageVolume, "pageVolume");
        return MvccDurablePageScan.scan(new MvccDurablePageScan.PageSource() {
            @Override
            public long pageCount() throws IOException {
                return pageVolume.pageCount();
            }

            @Override
            public DelosPage readPage(DelosPageId pageId) throws IOException {
                return pageVolume.readPage(pageId);
            }
        });
    }

    private <T> T readLockedIo(IoSupplier<T> action) throws IOException {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    private <T> T readLockedUnchecked(java.util.function.Supplier<T> action) {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    private <T> T writeLockedIo(IoSupplier<T> action) throws IOException {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void writeLockedIo(IoRunnable action) throws IOException {
        lock.writeLock().lock();
        try {
            action.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

    public record PageRecordStats(
            long pageCount,
            int slotCount,
            int wrappedRecordCount,
            int legacyRecordCount,
            int versionRecordCount,
            int nonVersionRecordCount) {
        public PageRecordStats {
            if (pageCount < 0L) {
                throw new IllegalArgumentException("pageCount must not be negative: " + pageCount);
            }
            if (slotCount < 0 || wrappedRecordCount < 0 || legacyRecordCount < 0
                    || versionRecordCount < 0 || nonVersionRecordCount < 0) {
                throw new IllegalArgumentException("MVCC page-record stats must not contain negative counts");
            }
            if (wrappedRecordCount + legacyRecordCount != slotCount) {
                throw new IllegalArgumentException("wrapped + legacy record counts must equal slot count");
            }
            if (versionRecordCount + nonVersionRecordCount != slotCount) {
                throw new IllegalArgumentException("version + non-version record counts must equal slot count");
            }
        }

        public boolean containsOnlyWrappedVersionRecords() {
            return slotCount == wrappedRecordCount && slotCount == versionRecordCount && legacyRecordCount == 0
                    && nonVersionRecordCount == 0;
        }
    }

    private record EncodedVersion(MvccVersionRecord record, byte[] bytes) {
        private EncodedVersion {
            record = Objects.requireNonNull(record, "record");
            bytes = Objects.requireNonNull(bytes, "bytes");
        }
    }

    public record StoredVersionRecord(MvccVersionLocator locator, MvccVersionRecord record) {
        public StoredVersionRecord {
            locator = Objects.requireNonNull(locator, "locator");
            record = Objects.requireNonNull(record, "record");
        }
    }
}
