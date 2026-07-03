/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.durable.MvccReusablePageIndexStore

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
import java.util.Collections;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

/** Durable sidecar index of whole MVCC pages made reusable by vacuum. */
final class MvccReusablePageIndexStore {
    private static final int MAGIC = 0x444d4650; // DMFP
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = Integer.BYTES * 3 + Long.BYTES;
    private static final int CHECKSUM_BYTES = Integer.BYTES;

    private final Path path;

    private MvccReusablePageIndexStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    static MvccReusablePageIndexStore open(Path path) {
        return new MvccReusablePageIndexStore(path);
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
            throw new IllegalStateException("MVCC reusable-page index is truncated: " + path);
        }
        int storedChecksum = ByteBuffer.wrap(bytes, bytes.length - CHECKSUM_BYTES, CHECKSUM_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt();
        int actualChecksum = MvccSidecarFiles.checksum(bytes, 0, bytes.length - CHECKSUM_BYTES);
        if (storedChecksum != actualChecksum) {
            throw new IllegalStateException("MVCC reusable-page index checksum mismatch: " + path);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, bytes.length - CHECKSUM_BYTES).order(ByteOrder.BIG_ENDIAN);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalStateException("Unexpected MVCC reusable-page index magic: " + magic);
        }
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IllegalStateException("Unsupported MVCC reusable-page index version: " + version);
        }
        long pageCount = buffer.getLong();
        int reusablePageCount = buffer.getInt();
        if (reusablePageCount < 0) {
            throw new IllegalStateException("Invalid MVCC reusable-page index count: " + reusablePageCount);
        }
        int expectedBytes = HEADER_BYTES + Math.multiplyExact(reusablePageCount, Long.BYTES) + CHECKSUM_BYTES;
        if (expectedBytes != bytes.length) {
            throw new IllegalStateException("Invalid MVCC reusable-page index length: " + path);
        }
        NavigableSet<Long> reusablePageIds = new TreeSet<>();
        for (int index = 0; index < reusablePageCount; index++) {
            long pageId = buffer.getLong();
            if (pageId < 0L || pageId >= pageCount) {
                throw new IllegalStateException("MVCC reusable-page index contains out-of-range page "
                        + pageId + " for pageCount=" + pageCount);
            }
            reusablePageIds.add(pageId);
        }
        if (reusablePageIds.size() != reusablePageCount) {
            throw new IllegalStateException("MVCC reusable-page index contains duplicate page ids: " + path);
        }
        return new Snapshot(pageCount, reusablePageIds);
    }

    void rewrite(long pageCount, NavigableSet<Long> reusablePageIds) throws IOException {
        Objects.requireNonNull(reusablePageIds, "reusablePageIds");
        if (pageCount < 0L) {
            throw new IllegalArgumentException("pageCount must not be negative: " + pageCount);
        }
        for (Long pageId : reusablePageIds) {
            if (pageId == null || pageId < 0L || pageId >= pageCount) {
                throw new IllegalArgumentException("Reusable page id " + pageId
                        + " is outside pageCount=" + pageCount);
            }
        }
        int payloadLength = HEADER_BYTES + Math.multiplyExact(reusablePageIds.size(), Long.BYTES);
        ByteBuffer buffer = ByteBuffer.allocate(payloadLength + CHECKSUM_BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putInt(VERSION);
        buffer.putLong(pageCount);
        buffer.putInt(reusablePageIds.size());
        for (Long pageId : reusablePageIds) {
            buffer.putLong(pageId);
        }
        buffer.putInt(MvccSidecarFiles.checksum(buffer.array(), 0, payloadLength));

        MvccSidecarFiles.rewriteAtomically(path, buffer.array());
    }

    void delete() throws IOException {
        MvccSidecarFiles.deleteWithRewriteSibling(path);
    }

    record Snapshot(long pageCount, NavigableSet<Long> reusablePageIds) {
        Snapshot {
            if (pageCount < 0L) {
                throw new IllegalArgumentException("pageCount must not be negative: " + pageCount);
            }
            reusablePageIds = Collections.unmodifiableNavigableSet(
                    new TreeSet<>(Objects.requireNonNull(reusablePageIds, "reusablePageIds")));
        }

        static Snapshot empty() {
            return new Snapshot(0L, new TreeSet<>());
        }
    }
}
