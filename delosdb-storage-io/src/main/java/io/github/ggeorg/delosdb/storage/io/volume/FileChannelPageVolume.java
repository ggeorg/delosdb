package io.github.ggeorg.delosdb.storage.io.volume;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageIo;

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
 * FileChannel-backed implementation of the raw DelosDB page-volume contract.
 *
 * <p>This class owns only storage-level file I/O: page count, page allocation,
 * complete page reads/writes, force policy, and close. It deliberately does not
 * wrap MVCC classes and carries no transaction, visibility, SQL, heap, provider,
 * or recovery-policy semantics.</p>
 */
public final class FileChannelPageVolume implements DelosPageVolume {
    private final Path path;
    private final FileChannel channel;
    private final SyncPolicy syncPolicy;
    private final ReentrantLock lock = new ReentrantLock();

    private FileChannelPageVolume(Path path, FileChannel channel, SyncPolicy syncPolicy) {
        this.path = Objects.requireNonNull(path, "path");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.syncPolicy = Objects.requireNonNull(syncPolicy, "syncPolicy");
    }

    public static FileChannelPageVolume open(Path path) throws IOException {
        return open(path, SyncPolicy.FULL);
    }

    public static FileChannelPageVolume open(Path path, SyncPolicy syncPolicy) throws IOException {
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
        FileChannelPageVolume volume = new FileChannelPageVolume(path, channel, syncPolicy);
        volume.verifyAligned();
        return volume;
    }

    @Override
    public DelosPage readPage(DelosPageId id) throws IOException {
        lock.lock();
        try {
            Objects.requireNonNull(id, "id");
            long count = pageCount();
            if (id.value() >= count) {
                throw new EOFException("page " + id.value() + " outside page file; pageCount=" + count);
            }
            ByteBuffer buffer = ByteBuffer.allocate(DelosPage.PAGE_SIZE);
            readFully(buffer, id.byteOffset(DelosPage.PAGE_SIZE));
            return DelosPageIo.decode(buffer.array(), id);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writePage(DelosPage page) throws IOException {
        lock.lock();
        try {
            Objects.requireNonNull(page, "page");
            ByteBuffer buffer = ByteBuffer.wrap(page.toBytes());
            writeFully(buffer, page.pageId().byteOffset(DelosPage.PAGE_SIZE));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public DelosPage allocatePage(int pageType) throws IOException {
        lock.lock();
        try {
            DelosPage page = DelosPage.empty(new DelosPageId(pageCount()), pageType);
            writePage(page);
            return page;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long pageCount() throws IOException {
        lock.lock();
        try {
            long size = channel.size();
            if (size % DelosPage.PAGE_SIZE != 0L) {
                throw new IllegalStateException(
                        "Delos page volume has torn length " + size + " at " + path);
            }
            return size / DelosPage.PAGE_SIZE;
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

    private void verifyAligned() throws IOException {
        pageCount();
    }

    private void writeFully(ByteBuffer buffer, long offset) throws IOException {
        long position = offset;
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer, position);
            if (written <= 0) {
                throw new IOException("failed to make progress writing page volume " + path + " at offset " + position);
            }
            position += written;
        }
    }

    private void readFully(ByteBuffer buffer, long offset) throws IOException {
        long position = offset;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new EOFException("unexpected end of Delos page volume " + path + " at offset " + position);
            }
            if (read == 0) {
                throw new EOFException("failed to make progress reading Delos page volume " + path + " at offset " + position);
            }
            position += read;
        }
    }
}
