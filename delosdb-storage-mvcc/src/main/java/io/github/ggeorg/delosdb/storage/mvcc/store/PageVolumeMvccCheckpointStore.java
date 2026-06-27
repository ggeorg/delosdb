/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccCheckpointStore

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

package io.github.ggeorg.delosdb.storage.mvcc.store;

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


/**
 * Small Derby-visible checkpoint metadata store for inherited MVCC tables.
 *
 * <p>MODULE14 deliberately checkpoints the inherited Derby provider boundary,
 * not a side MVCC engine: the checkpoint binds a provider storage id
 * to the page-volume file, row-directory sidecar, mutation log, WAL, and row
 * head digest. Version pages and
 * row-directory records remain the storage authority; this file is a compact
 * recovery contract and validation boundary.</p>
 */
public final class PageVolumeMvccCheckpointStore {
    private static final String MAGIC = "DELOS_INHERITED_MVCC_CHECKPOINT";
    private static final String VERSION = "1";

    private final Path path;
    private final String storageId;

    private PageVolumeMvccCheckpointStore(Path path, String storageId) {
        this.path = path;
        this.storageId = Objects.requireNonNull(storageId, "storageId");
    }

    public static PageVolumeMvccCheckpointStore open(Path databaseDirectory, String storageId) {
        Path file = PageVolumeMvccPaths.checkpointFile(databaseDirectory, storageId);
        if (file == null || storageId == null || storageId.isBlank()) {
            return disabled(storageId == null ? "disabled" : storageId);
        }
        return new PageVolumeMvccCheckpointStore(file, storageId);
    }

    public static PageVolumeMvccCheckpointStore disabled(String storageId) {
        return new PageVolumeMvccCheckpointStore(null, storageId == null ? "disabled" : storageId);
    }

    public Path path() {
        return path;
    }

    boolean enabled() {
        return path != null;
    }

    public Status validate(
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

    public void rewrite(
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
            properties.setProperty("segment", storageId);
            properties.setProperty("container", storageId);
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

    public void delete() throws IOException {
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

    public enum Status {
        DISABLED,
        ABSENT,
        WRITTEN,
        VALID,
        FALLBACK
    }

    private final class Checkpoint {
        private final Properties properties;

        private Checkpoint(Properties properties) {
            this.properties = Objects.requireNonNull(properties, "properties");
        }

        private void requireMatches(
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
            require("segment", storageId);
            require("container", storageId);
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
