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
    private long freeSpaceMapLookupCount;
    private long freeSpaceMapHitCount;
    private long freeSpaceMapNonLastHitCount;
    private long freeSpaceMapMissCount;
    private long freeSpaceMapStaleEntryCount;
    private long freeSpaceMapUpdateCount;
    private long freeSpaceMapRebuildCount;
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
            MvccPageCache pageCache) {
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
                new MvccPageCache());
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
                    new MvccPageCache());
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
        return writeLockedIo(() -> appendUnlocked(record, pageLsn));
    }

    private MvccVersionLocator appendUnlocked(
            MvccVersionRecord record,
            DelosLogSequenceNumber pageLsn) throws IOException {
        Objects.requireNonNull(record, "record");
        pageLsn = Objects.requireNonNull(pageLsn, "pageLsn");
        EncodedVersion encodedVersion = encodeForPageRecord(record);
        byte[] encoded = encodedVersion.bytes();
        record = encodedVersion.record();

        DelosPage page = writablePage(encoded.length);
        int slotId = page.appendRecord(encoded);
        DelosPage writtenPage = page.withPageLsn(pageLsn.value());
        writePage(writtenPage);
        updateFreeSpaceMap(writtenPage);
        pageVolume.force();
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
                    new MvccPageCache())) {
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
            DelosPage page = DelosPage.empty(new DelosPageId(pageId), DelosPage.DATA_PAGE_TYPE);
            for (MvccVersionRecord record : retainedPageRecords) {
                EncodedVersion encodedVersion = encodeForPageRecord(record);
                byte[] encoded = encodedVersion.bytes();
                int requiredBytes = Math.addExact(encoded.length, SLOT_OVERHEAD_BYTES);
                if (page.freeBytes() < requiredBytes) {
                    throw new IllegalStateException("retained MVCC records no longer fit on page " + pageId
                            + " during page-local prune; required=" + requiredBytes
                            + ", free=" + page.freeBytes());
                }
                page.appendRecord(encoded);
            }
            writePage(page);
            if (page.slotCount() == 0) {
                reusablePageIds.add(pageId);
            } else {
                reusablePageIds.remove(pageId);
            }
            persistReusablePageIndex();
            updateFreeSpaceMap(page);
            pageVolume.force();
            return loadAllUnlocked();
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

    private EncodedVersion encodeForPageRecord(MvccVersionRecord record) throws IOException {
        byte[] encoded = MvccPageRecordCodec.encodeVersionRecord(Objects.requireNonNull(record, "record"));
        if (encoded.length <= maxSingleRecordBytes()) {
            return new EncodedVersion(record, encoded);
        }

        MvccRowPayload payload = MvccRowPayloadCodec.decode(record.payload());
        MvccOverflowPayloadDescriptor descriptor = overflowStore.write(record.payload());
        byte[] referencePayload = MvccOverflowPayloadReferenceCodec.encode(
                new MvccOverflowPayloadReferenceCodec.Reference(payload.key(), descriptor));
        MvccVersionRecord referenceRecord = new MvccVersionRecord(record.header(), referencePayload);
        byte[] referenceBytes = encodeAndRequireSinglePageRecord(referenceRecord);
        return new EncodedVersion(referenceRecord, referenceBytes);
    }

    private StoredVersionRecord readStoredVersionRecord(
            MvccVersionLocator locator,
            MvccVersionRecord record) throws IOException {
        byte[] payload = record.payload();
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

    private static int maxSingleRecordBytes() {
        return DelosPage.empty(new DelosPageId(0L)).freeBytes() - SLOT_OVERHEAD_BYTES;
    }

    private DelosPage writablePage(int encodedRecordLength) throws IOException {
        int requiredBytes = Math.addExact(encodedRecordLength, SLOT_OVERHEAD_BYTES);
        DelosPage mapped = pageFromFreeSpaceMap(requiredBytes);
        if (mapped != null) {
            return mapped;
        }
        DelosPage reusable = takeReusablePage(requiredBytes);
        if (reusable != null) {
            return reusable;
        }
        long count = pageVolume.pageCount();
        if (count == 0) {
            return allocatePage(DelosPage.DATA_PAGE_TYPE);
        }
        DelosPage last = readPage(new DelosPageId(count - 1L));
        if (last.freeBytes() >= requiredBytes) {
            return last;
        }
        return allocatePage(DelosPage.DATA_PAGE_TYPE);
    }

    private DelosPage pageFromFreeSpaceMap(int requiredBytes) throws IOException {
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
                if (reusablePageIds.remove(pageNumber)) {
                    persistReusablePageIndex();
                }
                if (indexedFreeBytes != page.freeBytes()) {
                    entry.setValue(page.freeBytes());
                    changed = true;
                }
                if (changed) {
                    persistFreeSpaceMap();
                }
                return page;
            }
            entry.setValue(page.freeBytes());
            freeSpaceMapStaleEntryCount++;
            changed = true;
        }
        freeSpaceMapMissCount++;
        if (changed) {
            persistFreeSpaceMap();
        }
        return null;
    }

    private DelosPage takeReusablePage(int requiredBytes) throws IOException {
        boolean changed = false;
        java.util.Iterator<Long> ids = reusablePageIds.iterator();
        while (ids.hasNext()) {
            long pageNumber = ids.next();
            DelosPage page = readPage(new DelosPageId(pageNumber));
            ids.remove();
            changed = true;
            if (page.slotCount() == 0 && page.freeBytes() >= requiredBytes) {
                persistReusablePageIndex();
                return page;
            }
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
        pageVolume.force();
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
        reusablePageIndexStore.rewrite(pageVolume.pageCount(), reusablePageIds);
    }

    private void updateFreeSpaceMap(DelosPage page) throws IOException {
        freeBytesByPageId.put(page.pageId().value(), page.freeBytes());
        freeSpaceMapUpdateCount++;
        persistFreeSpaceMap();
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

    private DelosPage readPage(DelosPageId pageId) throws IOException {
        return pageCache.read(pageVolume, pageId);
    }

    private void writePage(DelosPage page) throws IOException {
        pageVolume.writePage(page);
        pageCache.put(page);
    }

    private DelosPage allocatePage(int pageType) throws IOException {
        DelosPage page = pageVolume.allocatePage(pageType);
        pageCache.put(page);
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
