/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.durable.MvccVisibilityMapStore

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
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.CRC32;

/** Durable sidecar visibility/prune map for page-backed MVCC data pages. */
final class MvccVisibilityMapStore {
    static final int HAS_OLD_VERSIONS = 1;
    static final int HAS_PRUNABLE_VERSIONS = 1 << 1;
    static final int ALL_VISIBLE = 1 << 2;
    static final int HAS_TOMBSTONES = 1 << 3;
    static final int HAS_OVERFLOW_REFERENCES = 1 << 4;
    static final int NEEDS_CHECKER = 1 << 5;

    private static final int MAGIC = 0x444d564d; // DMVM
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = Integer.BYTES * 3 + Long.BYTES;
    private static final int ENTRY_BYTES = Long.BYTES + Integer.BYTES + Integer.BYTES;
    private static final int CHECKSUM_BYTES = Integer.BYTES;

    private final Path path;

    private MvccVisibilityMapStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    static MvccVisibilityMapStore open(Path path) {
        return new MvccVisibilityMapStore(path);
    }

    Path path() {
        return path;
    }

    boolean exists() {
        return Files.exists(path);
    }

    Snapshot read() throws IOException {
        if (!Files.exists(path)) {
            return Snapshot.empty();
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < HEADER_BYTES + CHECKSUM_BYTES) {
            throw new IllegalStateException("MVCC visibility map is truncated: " + path);
        }
        int storedChecksum = ByteBuffer.wrap(bytes, bytes.length - CHECKSUM_BYTES, CHECKSUM_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt();
        int actualChecksum = checksum(bytes, 0, bytes.length - CHECKSUM_BYTES);
        if (storedChecksum != actualChecksum) {
            throw new IllegalStateException("MVCC visibility map checksum mismatch: " + path);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, bytes.length - CHECKSUM_BYTES).order(ByteOrder.BIG_ENDIAN);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalStateException("Unexpected MVCC visibility map magic: " + magic);
        }
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IllegalStateException("Unsupported MVCC visibility map version: " + version);
        }
        long pageCount = buffer.getLong();
        int entryCount = buffer.getInt();
        if (entryCount < 0) {
            throw new IllegalStateException("Invalid MVCC visibility map entry count: " + entryCount);
        }
        int expectedBytes = HEADER_BYTES + Math.multiplyExact(entryCount, ENTRY_BYTES) + CHECKSUM_BYTES;
        if (expectedBytes != bytes.length) {
            throw new IllegalStateException("Invalid MVCC visibility map length: " + path);
        }
        NavigableMap<Long, PageState> pageStates = new TreeMap<>();
        for (int index = 0; index < entryCount; index++) {
            long pageId = buffer.getLong();
            int flags = buffer.getInt();
            int versionCount = buffer.getInt();
            if (pageId < 0L || pageId >= pageCount) {
                throw new IllegalStateException("MVCC visibility map contains out-of-range page "
                        + pageId + " for pageCount=" + pageCount);
            }
            if (versionCount < 0) {
                throw new IllegalStateException("MVCC visibility map contains negative version count for page "
                        + pageId + ": " + versionCount);
            }
            PageState previous = pageStates.put(pageId, new PageState(flags, versionCount));
            if (previous != null) {
                throw new IllegalStateException("MVCC visibility map contains duplicate page id " + pageId);
            }
        }
        return new Snapshot(pageCount, pageStates);
    }

    void rewrite(long pageCount, NavigableMap<Long, PageState> pageStates) throws IOException {
        Objects.requireNonNull(pageStates, "pageStates");
        if (pageCount < 0L) {
            throw new IllegalArgumentException("pageCount must not be negative: " + pageCount);
        }
        for (var entry : pageStates.entrySet()) {
            Long pageId = entry.getKey();
            PageState state = entry.getValue();
            if (pageId == null || pageId < 0L || pageId >= pageCount) {
                throw new IllegalArgumentException("Visibility map page id " + pageId
                        + " is outside pageCount=" + pageCount);
            }
            Objects.requireNonNull(state, "state");
        }
        int payloadLength = HEADER_BYTES + Math.multiplyExact(pageStates.size(), ENTRY_BYTES);
        ByteBuffer buffer = ByteBuffer.allocate(payloadLength + CHECKSUM_BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putInt(VERSION);
        buffer.putLong(pageCount);
        buffer.putInt(pageStates.size());
        for (var entry : pageStates.entrySet()) {
            buffer.putLong(entry.getKey());
            buffer.putInt(entry.getValue().flags());
            buffer.putInt(entry.getValue().versionCount());
        }
        buffer.putInt(checksum(buffer.array(), 0, payloadLength));

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path rewritePath = path.resolveSibling(path.getFileName() + ".rewrite");
        Files.write(rewritePath, buffer.array());
        try {
            Files.move(rewritePath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            Files.move(rewritePath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void delete() throws IOException {
        Files.deleteIfExists(path);
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".rewrite"));
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes, offset, length);
        return (int) checksum.getValue();
    }

    record PageState(int flags, int versionCount) {
        PageState {
            if (versionCount < 0) {
                throw new IllegalArgumentException("versionCount must not be negative: " + versionCount);
            }
        }

        boolean hasFlag(int flag) {
            return (flags & flag) != 0;
        }
    }

    record Snapshot(long pageCount, NavigableMap<Long, PageState> pageStates) {
        Snapshot {
            if (pageCount < 0L) {
                throw new IllegalArgumentException("pageCount must not be negative: " + pageCount);
            }
            pageStates = Collections.unmodifiableNavigableMap(
                    new TreeMap<>(Objects.requireNonNull(pageStates, "pageStates")));
        }

        static Snapshot empty() {
            return new Snapshot(0L, new TreeMap<>());
        }
    }
}
