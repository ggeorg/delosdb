/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.durable.MvccDurableFiles

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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Shared crash-safe file publication primitives for MVCC durable state. */
public final class MvccDurableFiles {
    private MvccDurableFiles() {
    }

    public static void ensureParentDirectory(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public static void writeForced(Path path, byte[] bytes) throws IOException {
        writeForced(path, bytes, MvccSidecarFlushPolicy.immediate());
    }

    static void writeForced(
            Path path,
            byte[] bytes,
            MvccSidecarFlushPolicy flushPolicy) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bytes, "bytes");
        MvccSidecarFlushPolicy policy = MvccSidecarFlushPolicy.require(flushPolicy);
        ensureParentDirectory(path);
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writeFully(channel, ByteBuffer.wrap(bytes));
            policy.force(channel, path);
            MvccCommitDurabilityMetrics.recordFileForce(path, bytes.length);
        }
    }


    public static void appendForced(
            Path path,
            byte[] bytes,
            MvccSidecarFlushPolicy flushPolicy) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bytes, "bytes");
        MvccSidecarFlushPolicy policy = MvccSidecarFlushPolicy.require(flushPolicy);
        ensureParentDirectory(path);
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            writeFully(channel, ByteBuffer.wrap(bytes));
            policy.force(channel, path);
            MvccCommitDurabilityMetrics.recordFileForce(path, bytes.length);
        }
    }

    public static void rewriteAtomically(Path path, byte[] bytes, String temporarySuffix) throws IOException {
        rewriteAtomically(path, bytes, temporarySuffix, MvccSidecarFlushPolicy.immediate());
    }

    static void rewriteAtomically(
            Path path,
            byte[] bytes,
            String temporarySuffix,
            MvccSidecarFlushPolicy flushPolicy) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(temporarySuffix, "temporarySuffix");
        if (temporarySuffix.isEmpty()) {
            throw new IllegalArgumentException("temporarySuffix must not be empty");
        }
        MvccSidecarFlushPolicy policy = MvccSidecarFlushPolicy.require(flushPolicy);
        ensureParentDirectory(path);
        Path temporaryPath = path.resolveSibling(path.getFileName() + temporarySuffix);
        Files.deleteIfExists(temporaryPath);
        try {
            writeForced(temporaryPath, bytes, policy);
            moveIntoPlace(temporaryPath, path);
            forceParentDirectoryIfSupported(path, policy);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public static void moveIntoPlace(Path source, Path target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void forceParentDirectoryIfSupported(Path path) throws IOException {
        forceParentDirectoryIfSupported(path, MvccSidecarFlushPolicy.immediate());
    }

    static void forceParentDirectoryIfSupported(
            Path path,
            MvccSidecarFlushPolicy flushPolicy) throws IOException {
        Objects.requireNonNull(path, "path");
        MvccSidecarFlushPolicy policy = MvccSidecarFlushPolicy.require(flushPolicy);
        Path parent = path.getParent();
        if (parent == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)) {
            policy.force(channel, parent);
            MvccCommitDurabilityMetrics.recordDirectoryForce();
        } catch (IOException ignored) {
            // Some platforms do not support forcing directories. File data has already been forced.
        }
    }

    public static void deleteWithTemporarySibling(Path path, String temporarySuffix) throws IOException {
        deleteWithTemporarySibling(path, temporarySuffix, MvccSidecarFlushPolicy.immediate());
    }

    static void deleteWithTemporarySibling(
            Path path,
            String temporarySuffix,
            MvccSidecarFlushPolicy flushPolicy) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(temporarySuffix, "temporarySuffix");
        MvccSidecarFlushPolicy policy = MvccSidecarFlushPolicy.require(flushPolicy);
        boolean deleted = Files.deleteIfExists(path);
        deleted |= Files.deleteIfExists(path.resolveSibling(path.getFileName() + temporarySuffix));
        if (deleted) {
            forceParentDirectoryIfSupported(path, policy);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
