/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.durable.MvccPurgeQueueStore

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;

/** Durable first-step purge queue for obsolete page-backed MVCC versions. */
final class MvccPurgeQueueStore {
    private static final int MAGIC = 0x444d5051; // DMPQ
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = Integer.BYTES * 3;
    private static final int ENTRY_BYTES = Long.BYTES * 4 + Integer.BYTES;

    private final Path path;

    private MvccPurgeQueueStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    static MvccPurgeQueueStore open(Path path) {
        return new MvccPurgeQueueStore(path);
    }

    Path path() {
        return path;
    }

    boolean exists() {
        return Files.exists(path);
    }

    Snapshot read() throws IOException {
        var payload = MvccSidecarCodec.readPayloadIfExists(path, HEADER_BYTES, "MVCC purge queue");
        if (payload.isEmpty()) {
            return Snapshot.empty();
        }

        ByteBuffer buffer = payload.orElseThrow();
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalStateException("Unexpected MVCC purge queue magic: " + magic);
        }
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IllegalStateException("Unsupported MVCC purge queue version: " + version);
        }
        int entryCount = buffer.getInt();
        if (entryCount < 0) {
            throw new IllegalStateException("Invalid MVCC purge queue entry count: " + entryCount);
        }
        int expectedBytes = HEADER_BYTES + Math.multiplyExact(entryCount, ENTRY_BYTES);
        if (expectedBytes != buffer.limit()) {
            throw new IllegalStateException("Invalid MVCC purge queue length: " + path);
        }
        List<Entry> entries = new ArrayList<>(entryCount);
        for (int index = 0; index < entryCount; index++) {
            long rowId = buffer.getLong();
            long versionId = buffer.getLong();
            long pageId = buffer.getLong();
            long previousVersionId = buffer.getLong();
            int flags = buffer.getInt();
            if (rowId <= 0L) {
                throw new IllegalStateException("MVCC purge queue contains invalid row id: " + rowId);
            }
            if (versionId <= 0L) {
                throw new IllegalStateException("MVCC purge queue contains invalid version id: " + versionId);
            }
            if (pageId < 0L) {
                throw new IllegalStateException("MVCC purge queue contains invalid page id: " + pageId);
            }
            entries.add(new Entry(rowId, versionId, pageId, previousVersionId, flags));
        }
        return new Snapshot(entries);
    }

    void rewrite(List<Entry> entries) throws IOException {
        Objects.requireNonNull(entries, "entries");
        int payloadLength = HEADER_BYTES + Math.multiplyExact(entries.size(), ENTRY_BYTES);
        ByteBuffer buffer = MvccSidecarCodec.allocatePayload(payloadLength);
        buffer.putInt(MAGIC);
        buffer.putInt(VERSION);
        buffer.putInt(entries.size());
        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            buffer.putLong(entry.rowId());
            buffer.putLong(entry.versionId());
            buffer.putLong(entry.pageId());
            buffer.putLong(entry.previousVersionId());
            buffer.putInt(entry.flags());
        }
        MvccSidecarCodec.rewritePayload(path, buffer, payloadLength);
    }

    void delete() throws IOException {
        MvccSidecarFiles.deleteWithRewriteSibling(path);
    }

    static Entry entryFor(PageBackedMvccTableStore.StoredVersionRecord stored) {
        Objects.requireNonNull(stored, "stored");
        MvccTupleHeader header = stored.record().header();
        return new Entry(
                header.rowId().value(),
                header.versionId().value(),
                stored.locator().pageId().value(),
                header.previousVersionId().value(),
                header.flags());
    }

    record Entry(long rowId, long versionId, long pageId, long previousVersionId, int flags) {
        Entry {
            if (rowId <= 0L) {
                throw new IllegalArgumentException("rowId must be positive: " + rowId);
            }
            if (versionId <= 0L) {
                throw new IllegalArgumentException("versionId must be positive: " + versionId);
            }
            if (pageId < 0L) {
                throw new IllegalArgumentException("pageId must not be negative: " + pageId);
            }
        }

        MvccRowId mvccRowId() {
            return new MvccRowId(rowId);
        }

        MvccVersionId mvccVersionId() {
            return new MvccVersionId(versionId);
        }
    }

    record Snapshot(List<Entry> entries) {
        Snapshot {
            entries = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(entries, "entries")));
        }

        int pendingCount() {
            return entries.size();
        }

        static Snapshot empty() {
            return new Snapshot(List.of());
        }
    }
}
