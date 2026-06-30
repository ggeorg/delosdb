package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactories;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactory;
import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordCodec;

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

    private PageBackedMvccTableStore(
            Path path,
            DelosPageVolumeFactory volumeFactory,
            DelosPageVolume pageVolume,
            MvccOverflowPayloadStore overflowStore,
            MvccReusablePageIndexStore reusablePageIndexStore,
            NavigableSet<Long> reusablePageIds) {
        this.path = Objects.requireNonNull(path, "path");
        this.overflowPath = overflowPath(path);
        this.volumeFactory = Objects.requireNonNull(volumeFactory, "volumeFactory");
        this.pageVolume = Objects.requireNonNull(pageVolume, "pageVolume");
        this.overflowStore = Objects.requireNonNull(overflowStore, "overflowStore");
        this.reusablePageIndexStore = Objects.requireNonNull(reusablePageIndexStore, "reusablePageIndexStore");
        this.reusablePageIds = Objects.requireNonNull(reusablePageIds, "reusablePageIds");
    }

    public static PageBackedMvccTableStore open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        DelosPageVolume pageVolume = FILE_VOLUME_FACTORY.open(path);
        MvccReusablePageIndexStore reusablePageIndexStore = MvccReusablePageIndexStore.open(
                reusablePageIndexPath(path));
        NavigableSet<Long> reusablePageIds = recoverReusablePageIds(pageVolume, reusablePageIndexStore);
        reusablePageIndexStore.rewrite(pageVolume.pageCount(), reusablePageIds);
        return new PageBackedMvccTableStore(
                path,
                FILE_VOLUME_FACTORY,
                pageVolume,
                MvccOverflowPayloadStore.open(overflowPath(path), FILE_VOLUME_FACTORY),
                reusablePageIndexStore,
                reusablePageIds);
    }

    static PageBackedMvccTableStore open(Path path, DelosPageVolume pageVolume) {
        try {
            MvccReusablePageIndexStore reusablePageIndexStore = MvccReusablePageIndexStore.open(
                    reusablePageIndexPath(path));
            NavigableSet<Long> reusablePageIds = recoverReusablePageIds(pageVolume, reusablePageIndexStore);
            reusablePageIndexStore.rewrite(pageVolume.pageCount(), reusablePageIds);
            return new PageBackedMvccTableStore(
                    path,
                    FILE_VOLUME_FACTORY,
                    pageVolume,
                    MvccOverflowPayloadStore.open(overflowPath(path), FILE_VOLUME_FACTORY),
                    reusablePageIndexStore,
                    reusablePageIds);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open MVCC overflow payload store for " + path, e);
        }
    }

    public synchronized MvccVersionLocator append(MvccVersionRecord record) throws IOException {
        return append(record, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccVersionLocator append(
            MvccVersionRecord record,
            DelosLogSequenceNumber pageLsn) throws IOException {
        Objects.requireNonNull(record, "record");
        pageLsn = Objects.requireNonNull(pageLsn, "pageLsn");
        EncodedVersion encodedVersion = encodeForPageRecord(record);
        byte[] encoded = encodedVersion.bytes();
        record = encodedVersion.record();

        DelosPage page = writablePage(encoded.length);
        int slotId = page.appendRecord(encoded);
        pageVolume.writePage(page.withPageLsn(pageLsn.value()));
        pageVolume.force();
        return new MvccVersionLocator(page.pageId(), slotId);
    }

    public synchronized List<StoredVersionRecord> loadAll() throws IOException {
        List<StoredVersionRecord> records = new ArrayList<>();
        long count = pageVolume.pageCount();
        for (long pageNumber = 0; pageNumber < count; pageNumber++) {
            DelosPage page = pageVolume.readPage(new DelosPageId(pageNumber));
            for (int slot = 0; slot < page.slotCount(); slot++) {
                byte[] payload = page.readRecord(slot);
                records.add(readStoredVersionRecord(
                        new MvccVersionLocator(page.pageId(), slot),
                        MvccVersionRecordCodec.decode(payload)));
            }
        }
        return records;
    }

    public synchronized List<StoredVersionRecord> rewrite(List<MvccVersionRecord> records) throws IOException {
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
                new TreeSet<>())) {
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
        overflowStore = MvccOverflowPayloadStore.open(overflowPath, volumeFactory);
        rebuildReusablePageIds();
        return loadAll();
    }

    public synchronized long pageCount() throws IOException {
        return pageVolume.pageCount();
    }

    public synchronized long overflowPageCount() throws IOException {
        return overflowStore.pageCount();
    }

    public synchronized long reusablePageCount() {
        return reusablePageIds.size();
    }

    synchronized List<String> reusablePageConsistencyErrors() throws IOException {
        List<String> errors = new ArrayList<>();
        long pageCount = pageVolume.pageCount();
        NavigableSet<Long> emptyPages = scanEmptyPageIds(pageVolume);
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
    }

    public synchronized Path reusablePageIndexPath() {
        return reusablePageIndexStore.path();
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        try {
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
    }


    static byte[] encodeAndRequireSinglePageRecord(MvccVersionRecord record) {
        byte[] encoded = MvccVersionRecordCodec.encode(Objects.requireNonNull(record, "record"));
        int maxRecordBytes = maxSingleRecordBytes();
        if (encoded.length > maxRecordBytes) {
            throw new IllegalArgumentException("MVCC version record is too large for one page: "
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

    private EncodedVersion encodeForPageRecord(MvccVersionRecord record) throws IOException {
        byte[] encoded = MvccVersionRecordCodec.encode(Objects.requireNonNull(record, "record"));
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
        DelosPage reusable = takeReusablePage(encodedRecordLength);
        if (reusable != null) {
            return reusable;
        }
        long count = pageVolume.pageCount();
        if (count == 0) {
            return pageVolume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        }
        DelosPage last = pageVolume.readPage(new DelosPageId(count - 1L));
        if (last.freeBytes() >= encodedRecordLength + SLOT_OVERHEAD_BYTES) {
            return last;
        }
        return pageVolume.allocatePage(DelosPage.DATA_PAGE_TYPE);
    }

    private DelosPage takeReusablePage(int encodedRecordLength) throws IOException {
        int requiredBytes = Math.addExact(encodedRecordLength, SLOT_OVERHEAD_BYTES);
        boolean changed = false;
        java.util.Iterator<Long> ids = reusablePageIds.iterator();
        while (ids.hasNext()) {
            long pageNumber = ids.next();
            DelosPage page = pageVolume.readPage(new DelosPageId(pageNumber));
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
                pageVolume.writePage(rewriteVolume.readPage(new DelosPageId(pageNumber)));
            }
        }

        long pageCount = pageVolume.pageCount();
        for (long pageNumber = retainedPageCount; pageNumber < pageCount; pageNumber++) {
            pageVolume.writePage(DelosPage.empty(new DelosPageId(pageNumber), DelosPage.DATA_PAGE_TYPE));
        }
        pageVolume.force();
    }

    private void ensurePageCapacity(long requiredPageCount) throws IOException {
        while (pageVolume.pageCount() < requiredPageCount) {
            pageVolume.allocatePage(DelosPage.DATA_PAGE_TYPE);
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

    private static NavigableSet<Long> recoverReusablePageIds(
            DelosPageVolume pageVolume,
            MvccReusablePageIndexStore reusablePageIndexStore) throws IOException {
        Objects.requireNonNull(pageVolume, "pageVolume");
        Objects.requireNonNull(reusablePageIndexStore, "reusablePageIndexStore");
        NavigableSet<Long> emptyPages = scanEmptyPageIds(pageVolume);
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

    private static NavigableSet<Long> scanEmptyPageIds(DelosPageVolume pageVolume) throws IOException {
        NavigableSet<Long> reusablePages = new TreeSet<>();
        long count = pageVolume.pageCount();
        for (long pageNumber = 0L; pageNumber < count; pageNumber++) {
            DelosPage page = pageVolume.readPage(new DelosPageId(pageNumber));
            if (page.slotCount() == 0) {
                reusablePages.add(pageNumber);
            }
        }
        return reusablePages;
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
