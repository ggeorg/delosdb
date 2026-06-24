package io.github.ggeorg.delosdb.storage.mvcc.io;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provider-owned fixed-size page file for the experimental MVCC storage engine.
 */
public final class MvccPageFile implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final ReentrantLock lock = new ReentrantLock();

    private MvccPageFile(Path path, FileChannel channel) {
        this.path = Objects.requireNonNull(path, "path");
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    public static MvccPageFile open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        MvccPageFile pageFile = new MvccPageFile(path, channel);
        pageFile.verifyAligned();
        return pageFile;
    }

    public Path path() {
        return path;
    }

    public long pageCount() throws IOException {
        lock.lock();
        try {
            long size = channel.size();
            if (size % MvccPage.PAGE_SIZE != 0L) {
                throw new IllegalStateException(
                        "MVCC page file has torn length " + size + " at " + path);
            }
            return size / MvccPage.PAGE_SIZE;
        } finally {
            lock.unlock();
        }
    }

    public MvccPage allocatePage() throws IOException {
        return allocatePage(MvccPage.DATA_PAGE_TYPE);
    }

    public MvccPage allocatePage(int pageType) throws IOException {
        lock.lock();
        try {
            MvccPage page = MvccPage.empty(new MvccPageId(pageCount()), pageType);
            writePage(page);
            return page;
        } finally {
            lock.unlock();
        }
    }

    public void writePage(MvccPage page) throws IOException {
        lock.lock();
        try {
            Objects.requireNonNull(page, "page");
            ByteBuffer buffer = ByteBuffer.wrap(page.toBytes());
            writeFully(buffer, page.pageId().byteOffset(MvccPage.PAGE_SIZE));
        } finally {
            lock.unlock();
        }
    }

    public MvccPage readPage(MvccPageId pageId) throws IOException {
        lock.lock();
        try {
            Objects.requireNonNull(pageId, "pageId");
            long count = pageCount();
            if (pageId.value() >= count) {
                throw new EOFException("page " + pageId.value() + " outside page file; pageCount=" + count);
            }
            ByteBuffer buffer = ByteBuffer.allocate(MvccPage.PAGE_SIZE);
            readFully(buffer, pageId.byteOffset(MvccPage.PAGE_SIZE));
            return MvccPageIo.decode(buffer.array(), pageId);
        } finally {
            lock.unlock();
        }
    }

    public void force() throws IOException {
        lock.lock();
        try {
            channel.force(true);
        } finally {
            lock.unlock();
        }
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

    private void verifyAligned() throws IOException {
        pageCount();
    }

    private void writeFully(ByteBuffer buffer, long offset) throws IOException {
        long position = offset;
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
    }

    private void readFully(ByteBuffer buffer, long offset) throws IOException {
        long position = offset;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new EOFException("unexpected end of MVCC page file " + path + " at offset " + position);
            }
            position += read;
        }
    }
}
