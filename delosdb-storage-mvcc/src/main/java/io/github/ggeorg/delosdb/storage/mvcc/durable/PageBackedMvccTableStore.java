package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordCodec;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPage;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageFile;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageId;

/** Append-only page-backed store for durable MVCC version records. */
public final class PageBackedMvccTableStore implements AutoCloseable {
    private static final int SLOT_OVERHEAD_BYTES = 12;

    private MvccPageFile pageFile;

    private PageBackedMvccTableStore(MvccPageFile pageFile) {
        this.pageFile = Objects.requireNonNull(pageFile, "pageFile");
    }

    public static PageBackedMvccTableStore open(Path path) throws IOException {
        return new PageBackedMvccTableStore(MvccPageFile.open(path));
    }

    public synchronized MvccVersionLocator append(MvccVersionRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        byte[] encoded = MvccVersionRecordCodec.encode(record);
        if (encoded.length > maxSingleRecordBytes()) {
            throw new IllegalArgumentException("MVCC version record is too large for one page: " + encoded.length);
        }

        MvccPage page = writablePage(encoded.length);
        int slotId = page.appendRecord(encoded);
        pageFile.writePage(page);
        pageFile.force();
        return new MvccVersionLocator(page.pageId(), slotId);
    }

    public synchronized List<StoredVersionRecord> loadAll() throws IOException {
        List<StoredVersionRecord> records = new ArrayList<>();
        long count = pageFile.pageCount();
        for (long pageNumber = 0; pageNumber < count; pageNumber++) {
            MvccPage page = pageFile.readPage(new MvccPageId(pageNumber));
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
        Path path = pageFile.path();
        Path rewritePath = path.resolveSibling(path.getFileName() + ".rewrite");
        Files.deleteIfExists(rewritePath);
        try (PageBackedMvccTableStore rewrite = PageBackedMvccTableStore.open(rewritePath)) {
            for (MvccVersionRecord record : records) {
                rewrite.append(record);
            }
        }

        pageFile.close();
        Files.move(rewritePath, path, StandardCopyOption.REPLACE_EXISTING);
        pageFile = MvccPageFile.open(path);
        return loadAll();
    }

    public synchronized long pageCount() throws IOException {
        return pageFile.pageCount();
    }

    @Override
    public synchronized void close() throws IOException {
        pageFile.close();
    }

    private static int maxSingleRecordBytes() {
        return MvccPage.empty(new MvccPageId(0L)).freeBytes() - SLOT_OVERHEAD_BYTES;
    }

    private MvccPage writablePage(int encodedRecordLength) throws IOException {
        long count = pageFile.pageCount();
        if (count == 0) {
            return pageFile.allocatePage();
        }
        MvccPage last = pageFile.readPage(new MvccPageId(count - 1L));
        if (last.freeBytes() >= encodedRecordLength + SLOT_OVERHEAD_BYTES) {
            return last;
        }
        return pageFile.allocatePage();
    }

    public record StoredVersionRecord(MvccVersionLocator locator, MvccVersionRecord record) {
        public StoredVersionRecord {
            locator = Objects.requireNonNull(locator, "locator");
            record = Objects.requireNonNull(record, "record");
        }
    }
}
