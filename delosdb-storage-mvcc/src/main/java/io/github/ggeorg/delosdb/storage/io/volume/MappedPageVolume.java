package io.github.ggeorg.delosdb.storage.io.volume;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageIo;

import java.io.EOFException;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Optional memory-mapped implementation of the raw DelosDB page-volume contract.
 *
 * <p>This class is a benchmark-gated candidate, not the default storage backend.
 * It owns only file-backed page I/O and carries no transaction, visibility, SQL,
 * heap, provider, or recovery-policy semantics.</p>
 */
public final class MappedPageVolume implements DelosPageVolume {
    private final Path path;
    private final FileChannel channel;
    private final SyncPolicy syncPolicy;
    private final long maxPages;
    private final ReentrantLock lock = new ReentrantLock();
    private MappedByteBuffer mapped;
    private long pageCount;

    private MappedPageVolume(Path path, FileChannel channel, SyncPolicy syncPolicy, long maxPages)
            throws IOException {
        this.path = Objects.requireNonNull(path, "path");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.syncPolicy = Objects.requireNonNull(syncPolicy, "syncPolicy");
        if (maxPages <= 0L) {
            throw new IllegalArgumentException("maxPages must be positive: " + maxPages);
        }
        this.maxPages = maxPages;
        long size = channel.size();
        if (size % DelosPage.PAGE_SIZE != 0L) {
            throw new IllegalStateException("Delos mapped page volume has torn length " + size + " at " + path);
        }
        this.pageCount = size / DelosPage.PAGE_SIZE;
        if (pageCount > maxPages) {
            throw new IllegalStateException(
                    "Delos mapped page volume pageCount=" + pageCount + " exceeds maxPages=" + maxPages);
        }
        remap();
    }

    public static MappedPageVolume open(Path path, long maxPages) throws IOException {
        return open(path, SyncPolicy.FULL, maxPages);
    }

    public static MappedPageVolume open(Path path, SyncPolicy syncPolicy, long maxPages) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(syncPolicy, "syncPolicy");
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        return new MappedPageVolume(path, channel, syncPolicy, maxPages);
    }

    @Override
    public DelosPage readPage(DelosPageId id) throws IOException {
        lock.lock();
        try {
            Objects.requireNonNull(id, "id");
            if (id.value() >= pageCount) {
                throw new EOFException("page " + id.value() + " outside mapped page volume; pageCount=" + pageCount);
            }
            byte[] bytes = new byte[DelosPage.PAGE_SIZE];
            MappedByteBuffer view = mapped.duplicate();
            view.position(checkedIntOffset(id));
            view.get(bytes);
            return DelosPageIo.decode(bytes, id);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writePage(DelosPage page) throws IOException {
        lock.lock();
        try {
            Objects.requireNonNull(page, "page");
            if (page.pageId().value() >= pageCount) {
                throw new EOFException(
                        "page " + page.pageId().value() + " outside mapped page volume; pageCount=" + pageCount);
            }
            MappedByteBuffer view = mapped.duplicate();
            view.position(checkedIntOffset(page.pageId()));
            view.put(page.toBytes());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public DelosPage allocatePage(int pageType) throws IOException {
        lock.lock();
        try {
            if (pageCount >= maxPages) {
                throw new IOException("mapped page volume is full: pageCount=" + pageCount + ", maxPages=" + maxPages);
            }
            DelosPage page = DelosPage.empty(new DelosPageId(pageCount), pageType);
            growTo(pageCount + 1L);
            writePage(page);
            return page;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long pageCount() {
        lock.lock();
        try {
            return pageCount;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void force() throws IOException {
        lock.lock();
        try {
            if (syncPolicy == SyncPolicy.NONE) {
                return;
            }
            if (mapped != null) {
                mapped.force();
            }
            channel.force(syncPolicy == SyncPolicy.FULL);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public SyncPolicy syncPolicy() {
        return syncPolicy;
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            channel.close();
        } finally {
            lock.unlock();
        }
    }

    private void growTo(long newPageCount) throws IOException {
        long newSize = Math.multiplyExact(newPageCount, (long) DelosPage.PAGE_SIZE);
        if (newSize > 0L) {
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}), newSize - 1L);
        }
        pageCount = newPageCount;
        remap();
    }

    private void remap() throws IOException {
        if (pageCount == 0L) {
            mapped = null;
            return;
        }
        long bytes = Math.multiplyExact(pageCount, (long) DelosPage.PAGE_SIZE);
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "mapped page volume candidate currently supports at most " + Integer.MAX_VALUE
                            + " mapped bytes; requested " + bytes);
        }
        mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0L, bytes);
    }

    private int checkedIntOffset(DelosPageId id) {
        long offset = id.byteOffset(DelosPage.PAGE_SIZE);
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalStateException("mapped page offset exceeds candidate limit: " + offset);
        }
        return (int) offset;
    }
}
