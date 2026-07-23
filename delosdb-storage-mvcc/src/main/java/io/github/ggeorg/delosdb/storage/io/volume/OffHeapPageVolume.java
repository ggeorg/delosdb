package io.github.ggeorg.delosdb.storage.io.volume;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageIo;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory implementation of the raw DelosDB page-volume contract.
 *
 * <p>This volume is dense and page-id indexed. It deliberately has no file
 * identity and no durability boundary; {@link #force()} is a no-op and
 * {@link #syncPolicy()} always returns {@link SyncPolicy#NONE}.</p>
 *
 * <p>The volume stores encoded page images, not mutable page objects. Reads
 * decode a fresh page instance, and writes copy the supplied complete page
 * image. That keeps the in-memory implementation aligned with file-backed
 * complete-page semantics instead of exposing object aliasing.</p>
 */
public final class OffHeapPageVolume implements DelosPageVolume {
    private final ReentrantLock lock = new ReentrantLock();
    private final List<byte[]> pages = new ArrayList<>();
    private boolean closed;

    public static OffHeapPageVolume open() {
        return new OffHeapPageVolume();
    }

    private OffHeapPageVolume() {
    }

    @Override
    public DelosPage readPage(DelosPageId id) throws IOException {
        lock.lock();
        try {
            ensureOpen();
            Objects.requireNonNull(id, "id");
            int index = checkedExistingIndex(id);
            return DelosPageIo.decode(pages.get(index).clone(), id);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writePage(DelosPage page) throws IOException {
        lock.lock();
        try {
            ensureOpen();
            Objects.requireNonNull(page, "page");
            int index = checkedWritableIndex(page.pageId());
            byte[] encoded = page.toBytes();
            if (index == pages.size()) {
                pages.add(encoded);
            } else {
                pages.set(index, encoded);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public DelosPage allocatePage(int pageType) throws IOException {
        lock.lock();
        try {
            ensureOpen();
            DelosPage page = DelosPage.empty(new DelosPageId(pages.size()), pageType);
            pages.add(page.toBytes());
            return page;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long pageCount() throws IOException {
        lock.lock();
        try {
            ensureOpen();
            return pages.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void force() throws IOException {
        lock.lock();
        try {
            ensureOpen();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public SyncPolicy syncPolicy() {
        return SyncPolicy.NONE;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            pages.clear();
        } finally {
            lock.unlock();
        }
    }

    private int checkedExistingIndex(DelosPageId id) throws EOFException {
        long value = id.value();
        if (value >= pages.size()) {
            throw new EOFException("page " + value + " outside off-heap page volume; pageCount=" + pages.size());
        }
        if (value > Integer.MAX_VALUE) {
            throw new EOFException("page id outside supported off-heap index range: " + value);
        }
        return (int) value;
    }

    private int checkedWritableIndex(DelosPageId id) throws IOException {
        long value = id.value();
        if (value > pages.size()) {
            throw new EOFException("sparse write rejected for page " + value + "; pageCount=" + pages.size());
        }
        if (value > Integer.MAX_VALUE) {
            throw new IOException("page id outside supported off-heap index range: " + value);
        }
        return (int) value;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("off-heap page volume is closed");
        }
    }
}
