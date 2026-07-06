/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.durable.AbstractSidecarStore

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Shared lifecycle support for small durable MVCC sidecar stores and logs. */
public abstract class AbstractSidecarStore {
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path path;
    private final MvccSidecarFlushPolicy flushPolicy;

    protected AbstractSidecarStore(Path path) {
        this(path, MvccSidecarFlushPolicy.immediate());
    }

    protected AbstractSidecarStore(Path path, MvccSidecarFlushPolicy flushPolicy) {
        this.path = Objects.requireNonNull(path, "path");
        this.flushPolicy = MvccSidecarFlushPolicy.require(flushPolicy);
    }

    public static void ensureParentDirectory(Path path, String description) {
        Objects.requireNonNull(path, "path");
        Path parent = path.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + description + " directory: " + parent, e);
        }
    }

    public static String readUtf8IfExists(Path path, String description) {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + description + ": " + path, e);
        }
    }

    public static void appendUtf8Forced(Path path, String content, String description) {
        Objects.requireNonNull(content, "content");
        appendBytesForced(path, content.getBytes(StandardCharsets.UTF_8), description, MvccSidecarFlushPolicy.immediate());
    }

    public static void appendBytesForced(Path path, byte[] bytes, String description) {
        appendBytesForced(path, bytes, description, MvccSidecarFlushPolicy.immediate());
    }

    protected final Path sidecarPath() {
        return path;
    }

    protected final boolean sidecarExists() {
        return Files.exists(path);
    }

    protected final boolean sidecarHasBytes() throws IOException {
        return Files.exists(path) && Files.size(path) > 0L;
    }

    protected final Optional<ByteBuffer> readPayloadIfExists(
            int minimumPayloadBytes,
            String description) throws IOException {
        return MvccSidecarCodec.readPayloadIfExists(path, minimumPayloadBytes, description);
    }

    protected final ByteBuffer allocatePayload(int payloadLength) {
        return MvccSidecarCodec.allocatePayload(payloadLength);
    }

    protected final void rewritePayload(ByteBuffer payloadBuffer, int payloadLength) throws IOException {
        MvccSidecarCodec.rewritePayload(path, payloadBuffer, payloadLength);
    }

    protected final void deleteWithRewriteSibling() throws IOException {
        MvccSidecarFiles.deleteWithRewriteSibling(path);
    }

    protected final void ensureParentDirectory(String description) {
        ensureParentDirectory(path, description);
    }

    protected final String readUtf8IfExists(String description) {
        return readUtf8IfExists(path, description);
    }

    protected final List<String> readUtf8LinesIfExists(String description) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    protected final void appendUtf8Forced(String content, String description) {
        Objects.requireNonNull(content, "content");
        appendBytesForced(content.getBytes(StandardCharsets.UTF_8), description);
    }

    protected final void appendBytesForced(byte[] bytes, String description) {
        appendBytesForced(path, bytes, description, flushPolicy);
    }

    private static void appendBytesForced(
            Path path,
            byte[] bytes,
            String description,
            MvccSidecarFlushPolicy flushPolicy) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bytes, "bytes");
        MvccSidecarFlushPolicy.require(flushPolicy);
        ensureParentDirectory(path, description);
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            writeFully(channel, ByteBuffer.wrap(bytes));
            flushPolicy.force(channel, path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not append " + description + " to: " + path, e);
        }
    }

    protected final void rewriteUtf8AtomicallyForced(String content, String description) {
        Objects.requireNonNull(content, "content");
        rewriteBytesAtomicallyForced(content.getBytes(StandardCharsets.UTF_8), description);
    }

    protected final void rewriteBytesAtomicallyForced(byte[] bytes, String description) {
        Objects.requireNonNull(bytes, "bytes");
        ensureParentDirectory(description);
        Path temp = path.resolveSibling(path.getFileName() + TEMP_SUFFIX);
        try {
            Files.write(temp, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            forceFile(temp);
            moveIntoPlace(temp);
            forceParentDirectoryIfSupported();
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new UncheckedIOException("Could not rewrite " + description + ": " + path, failure);
        }
    }

    protected final void forceParentDirectoryIfSupported() throws IOException {
        Path parent = path.getParent() == null ? Path.of(".") : path.getParent();
        try (FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)) {
            flushPolicy.force(channel, parent);
        } catch (IOException ignored) {
            // Some platforms do not support forcing directories. File data has already been forced.
        }
    }

    private void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            flushPolicy.force(channel, file);
        }
    }

    private void moveIntoPlace(Path source) throws IOException {
        try {
            Files.move(source, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            try {
                Files.move(source, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                atomicMoveFailure.addSuppressed(fallbackFailure);
                throw atomicMoveFailure;
            }
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
