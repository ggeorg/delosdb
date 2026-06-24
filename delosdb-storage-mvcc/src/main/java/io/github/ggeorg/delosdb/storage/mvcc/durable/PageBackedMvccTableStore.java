package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactories;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactory;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordCodec;

/** Append-only page-backed store for durable MVCC version records. */
public final class PageBackedMvccTableStore implements AutoCloseable {
    private static final int SLOT_OVERHEAD_BYTES = 12;

    private static final DelosPageVolumeFactory FILE_VOLUME_FACTORY = DelosPageVolumeFactories.fileChannel();

    private final Path path;
    private final DelosPageVolumeFactory volumeFactory;
    private DelosPageVolume pageVolume;

    private PageBackedMvccTableStore(
            Path path,
            DelosPageVolumeFactory volumeFactory,
            DelosPageVolume pageVolume) {
        this.path = Objects.requireNonNull(path, "path");
        this.volumeFactory = Objects.requireNonNull(volumeFactory, "volumeFactory");
        this.pageVolume = Objects.requireNonNull(pageVolume, "pageVolume");
    }

    public static PageBackedMvccTableStore open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return new PageBackedMvccTableStore(path, FILE_VOLUME_FACTORY, FILE_VOLUME_FACTORY.open(path));
    }

    static PageBackedMvccTableStore open(Path path, DelosPageVolume pageVolume) {
        return new PageBackedMvccTableStore(path, FILE_VOLUME_FACTORY, pageVolume);
    }

    public synchronized MvccVersionLocator append(MvccVersionRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        byte[] encoded = MvccVersionRecordCodec.encode(record);
        if (encoded.length > maxSingleRecordBytes()) {
            throw new IllegalArgumentException("MVCC version record is too large for one page: " + encoded.length);
        }

        DelosPage page = writablePage(encoded.length);
        int slotId = page.appendRecord(encoded);
        pageVolume.writePage(page);
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
                records.add(new StoredVersionRecord(
                        new MvccVersionLocator(page.pageId(), slot),
                        MvccVersionRecordCodec.decode(payload)));
            }
        }
        return records;
    }

    public synchronized List<StoredVersionRecord> rewrite(List<MvccVersionRecord> records) throws IOException {
        Objects.requireNonNull(records, "records");
        Path rewritePath = path.resolveSibling(path.getFileName() + ".rewrite");
        Files.deleteIfExists(rewritePath);
        try (PageBackedMvccTableStore rewrite = new PageBackedMvccTableStore(
                rewritePath,
                volumeFactory,
                volumeFactory.open(rewritePath))) {
            for (MvccVersionRecord record : records) {
                rewrite.append(record);
            }
        }

        pageVolume.close();
        Files.move(rewritePath, path, StandardCopyOption.REPLACE_EXISTING);
        pageVolume = volumeFactory.open(path);
        return loadAll();
    }

    public synchronized long pageCount() throws IOException {
        return pageVolume.pageCount();
    }

    @Override
    public synchronized void close() throws IOException {
        pageVolume.close();
    }


    private static int maxSingleRecordBytes() {
        return DelosPage.empty(new DelosPageId(0L)).freeBytes() - SLOT_OVERHEAD_BYTES;
    }

    private DelosPage writablePage(int encodedRecordLength) throws IOException {
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

    public record StoredVersionRecord(MvccVersionLocator locator, MvccVersionRecord record) {
        public StoredVersionRecord {
            locator = Objects.requireNonNull(locator, "locator");
            record = Objects.requireNonNull(record, "record");
        }
    }
}
