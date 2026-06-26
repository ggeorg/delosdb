/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.InheritedMvccCheckpointStore

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

package org.apache.derby.impl.store.access.mvcc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowDirectoryStore;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;

import org.apache.derby.iapi.store.raw.ContainerKey;

/**
 * Small Derby-visible checkpoint metadata store for inherited MVCC tables.
 *
 * <p>MODULE14 deliberately checkpoints the inherited Derby provider boundary,
 * not a side MVCC engine: the checkpoint binds the Derby {@link ContainerKey}
 * to the page-volume file, row-directory sidecar, mutation log, WAL, and row
 * head digest currently used by {@link MvccConglomerateState}. Version pages and
 * row-directory records remain the storage authority; this file is a compact
 * recovery contract and validation boundary.</p>
 */
final class InheritedMvccCheckpointStore {
    private static final String MAGIC = "DELOS_INHERITED_MVCC_CHECKPOINT";
    private static final String VERSION = "1";

    private final Path path;
    private final ContainerKey key;

    private InheritedMvccCheckpointStore(Path path, ContainerKey key) {
        this.path = path;
        this.key = Objects.requireNonNull(key, "key");
    }

    static InheritedMvccCheckpointStore open(Path databaseDirectory, ContainerKey key) {
        Path file = checkpointFile(databaseDirectory, key);
        if (file == null || key.getContainerId() == 0L) {
            return disabled(key);
        }
        return new InheritedMvccCheckpointStore(file, key);
    }

    static InheritedMvccCheckpointStore disabled(ContainerKey key) {
        return new InheritedMvccCheckpointStore(null, key);
    }

    Path path() {
        return path;
    }

    boolean enabled() {
        return path != null;
    }

    Status validate(
            Path pageFile,
            Path rowDirectoryFile,
            Path pageMutationLogFile,
            Path writeAheadLogFile,
            Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> heads,
            long physicalVersionCount,
            long logicalRowCount,
            long nextRowId) {
        if (!enabled()) {
            return Status.DISABLED;
        }
        if (!Files.exists(path)) {
            return Status.ABSENT;
        }
        try {
            Checkpoint checkpoint = readCheckpoint();
            checkpoint.requireMatches(
                    key,
                    pageFile,
                    rowDirectoryFile,
                    pageMutationLogFile,
                    writeAheadLogFile,
                    heads,
                    physicalVersionCount,
                    logicalRowCount,
                    nextRowId);
            return Status.VALID;
        } catch (IOException | RuntimeException failure) {
            return Status.FALLBACK;
        }
    }

    void rewrite(
            Path pageFile,
            Path rowDirectoryFile,
            Path pageMutationLogFile,
            Path writeAheadLogFile,
            Collection<MvccRowDirectoryStore.RowHeadRecord> heads,
            long physicalVersionCount,
            long logicalRowCount,
            long nextRowId) {
        if (!enabled()) {
            return;
        }
        Objects.requireNonNull(heads, "heads");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Properties properties = new Properties();
            properties.setProperty("magic", MAGIC);
            properties.setProperty("version", VERSION);
            properties.setProperty("segment", Long.toString(key.getSegmentId()));
            properties.setProperty("container", Long.toString(key.getContainerId()));
            properties.setProperty("pageFile", fileName(pageFile));
            properties.setProperty("rowDirectoryFile", fileName(rowDirectoryFile));
            properties.setProperty("pageMutationLogFile", fileName(pageMutationLogFile));
            properties.setProperty("writeAheadLogFile", fileName(writeAheadLogFile));
            properties.setProperty("pageFileSize", Long.toString(size(pageFile)));
            properties.setProperty("rowDirectoryFileSize", Long.toString(size(rowDirectoryFile)));
            properties.setProperty("pageMutationLogFileSize", Long.toString(size(pageMutationLogFile)));
            properties.setProperty("writeAheadLogFileSize", Long.toString(size(writeAheadLogFile)));
            properties.setProperty("physicalVersionCount", Long.toString(physicalVersionCount));
            properties.setProperty("logicalRowCount", Long.toString(logicalRowCount));
            properties.setProperty("headCount", Integer.toString(heads.size()));
            properties.setProperty("nextRowId", Long.toString(nextRowId));
            properties.setProperty("rowHeadDigest", rowHeadDigest(heads));
            String content = properties.stringPropertyNames().stream()
                    .sorted()
                    .map(name -> name + "=" + properties.getProperty(name))
                    .collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator()));
            Path rewrite = path.resolveSibling(path.getFileName() + ".rewrite");
            Files.writeString(rewrite, content, StandardCharsets.UTF_8);
            Files.move(rewrite, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write inherited MVCC checkpoint: " + path, e);
        }
    }

    void delete() throws IOException {
        if (enabled()) {
            Files.deleteIfExists(path);
        }
    }

    private Checkpoint readCheckpoint() throws IOException {
        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return new Checkpoint(properties);
    }

    private static Path checkpointFile(Path databaseDirectory, ContainerKey key) {
        Path directory = InheritedMvccPageVolumeStateStore.inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + key.getSegmentId() + "-" + key.getContainerId() + ".checkpoint");
    }

    private static String fileName(Path path) {
        return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
    }

    private static long size(Path path) throws IOException {
        return path == null || !Files.exists(path) ? 0L : Files.size(path);
    }

    private static String rowHeadDigest(Collection<MvccRowDirectoryStore.RowHeadRecord> heads) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            heads.stream()
                    .sorted(java.util.Comparator.comparingLong(head -> head.rowId().value()))
                    .forEach(head -> updateDigest(digest, head));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for inherited MVCC checkpoint digest", e);
        }
    }

    private static void updateDigest(MessageDigest digest, MvccRowDirectoryStore.RowHeadRecord head) {
        digest.update(Long.toString(head.rowId().value()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '|');
        digest.update(head.key().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '|');
        digest.update(Long.toString(head.headVersionId().value()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '|');
        digest.update(Long.toString(head.previousVersionId().value()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '|');
        digest.update(Long.toString(head.headLocator().pageId().value()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '|');
        digest.update(Integer.toString(head.headLocator().slotId()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '|');
        digest.update((byte) (head.tombstone() ? 1 : 0));
        digest.update((byte) '\n');
    }

    enum Status {
        DISABLED,
        ABSENT,
        WRITTEN,
        VALID,
        FALLBACK
    }

    private static final class Checkpoint {
        private final Properties properties;

        private Checkpoint(Properties properties) {
            this.properties = Objects.requireNonNull(properties, "properties");
        }

        private void requireMatches(
                ContainerKey key,
                Path pageFile,
                Path rowDirectoryFile,
                Path pageMutationLogFile,
                Path writeAheadLogFile,
                Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> heads,
                long physicalVersionCount,
                long logicalRowCount,
                long nextRowId) throws IOException {
            require("magic", MAGIC);
            require("version", VERSION);
            require("segment", Long.toString(key.getSegmentId()));
            require("container", Long.toString(key.getContainerId()));
            require("pageFile", fileName(pageFile));
            require("rowDirectoryFile", fileName(rowDirectoryFile));
            require("pageMutationLogFile", fileName(pageMutationLogFile));
            require("writeAheadLogFile", fileName(writeAheadLogFile));
            require("pageFileSize", Long.toString(size(pageFile)));
            require("rowDirectoryFileSize", Long.toString(size(rowDirectoryFile)));
            require("pageMutationLogFileSize", Long.toString(size(pageMutationLogFile)));
            require("writeAheadLogFileSize", Long.toString(size(writeAheadLogFile)));
            require("physicalVersionCount", Long.toString(physicalVersionCount));
            require("logicalRowCount", Long.toString(logicalRowCount));
            require("headCount", Integer.toString(heads.size()));
            require("nextRowId", Long.toString(nextRowId));
            require("rowHeadDigest", rowHeadDigest(heads.values()));
        }

        private void require(String key, String expected) {
            String actual = properties.getProperty(key);
            if (!Objects.equals(actual, expected)) {
                throw new IllegalStateException("Inherited MVCC checkpoint mismatch for " + key
                        + ": expected " + expected + " but found " + actual);
            }
        }
    }
}
