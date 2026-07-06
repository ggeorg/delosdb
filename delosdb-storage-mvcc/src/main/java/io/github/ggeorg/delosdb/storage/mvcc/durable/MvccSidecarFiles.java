/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.durable.MvccSidecarFiles

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.zip.CRC32;

/** Shared file helpers for small MVCC sidecar files with checksum trailers. */
final class MvccSidecarFiles {
    private static final String REWRITE_SUFFIX = ".rewrite";

    private MvccSidecarFiles() {
    }

    static int checksum(byte[] bytes, int offset, int length) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes, offset, length);
        return (int) checksum.getValue();
    }

    static void rewriteAtomically(Path path, byte[] bytes) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bytes, "bytes");

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path rewritePath = rewritePath(path);
        Files.deleteIfExists(rewritePath);
        try {
            writeFileForced(rewritePath, bytes);
            try {
                Files.move(rewritePath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(rewritePath, path, StandardCopyOption.REPLACE_EXISTING);
            }
            forceParentDirectoryIfSupported(path);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(rewritePath);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    static void deleteWithRewriteSibling(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Files.deleteIfExists(path);
        Files.deleteIfExists(rewritePath(path));
    }

    private static Path rewritePath(Path path) {
        return path.resolveSibling(path.getFileName() + REWRITE_SUFFIX);
    }

    private static void writeFileForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writeFully(channel, ByteBuffer.wrap(bytes));
            channel.force(true);
        }
    }

    private static void forceParentDirectoryIfSupported(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // Some platforms do not support forcing directories. The sidecar file itself has already been forced.
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
