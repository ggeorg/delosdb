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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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
 * <p>The checkpoint file is a compact recovery contract that binds the provider
 * storage id to the page-volume file, row-directory sidecar, mutation log, WAL,
 * and row-head digest. Version pages and row-directory records remain the
 * storage authority.</p>
 *
 * <p>The R1 checkpoint lifecycle adds a forced prepare marker before publishing
 * the checkpoint and a forced completion marker after publication. This does not
 * turn the checkpoint file into storage authority; it makes interrupted
 * checkpoint publication observable and recoverable by falling back to the
 * durable page state.</p>
 */
public final class PageVolumeMvccCheckpointStore {
    private static final String MAGIC = "DELOS_INHERITED_MVCC_CHECKPOINT";
    private static final String VERSION = "2";
    private static final String LIFECYCLE_MAGIC = "DELOS_INHERITED_MVCC_CHECKPOINT_LIFECYCLE";

    private final Path path;
    private final Path pendingPath;
    private final Path lifecyclePath;
    private final String storageId;

    private PageVolumeMvccCheckpointStore(Path path, Path pendingPath, Path lifecyclePath, String storageId) {
        this.path = path;
        this.pendingPath = pendingPath;
        this.lifecyclePath = lifecyclePath;
        this.storageId = Objects.requireNonNull(storageId, "storageId");
    }

    public static PageVolumeMvccCheckpointStore open(Path databaseDirectory, String storageId) {
        if (databaseDirectory == null || PageVolumeMvccPaths.isMissingStorageId(storageId)) {
            return disabled(storageId == null ? "disabled" : storageId);
        }
        Path file = PageVolumeMvccPaths.checkpointFile(databaseDirectory, storageId);
        if (file == null) {
            return disabled(storageId == null ? "disabled" : storageId);
        }
        return new PageVolumeMvccCheckpointStore(
                file,
                PageVolumeMvccPaths.checkpointPendingFile(databaseDirectory, storageId),
                PageVolumeMvccPaths.checkpointLifecycleFile(databaseDirectory, storageId),
                storageId);
    }

    public static PageVolumeMvccCheckpointStore disabled(String storageId) {
        return new PageVolumeMvccCheckpointStore(null, null, null, storageId == null ? "disabled" : storageId);
    }

    public Path path() {
        return path;
    }

    public Path pendingPath() {
        return pendingPath;
    }

    public Path lifecyclePath() {
        return lifecyclePath;
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
        if (hasInterruptedLifecycle()) {
            return Status.INCOMPLETE;
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
            long generation = nextGeneration();
            String checkpointContent = checkpointContent(
                    generation,
                    pageFile,
                    rowDirectoryFile,
                    pageMutationLogFile,
                    writeAheadLogFile,
                    heads,
                    physicalVersionCount,
                    logicalRowCount,
                    nextRowId);
            writeUtf8Forced(pendingPath, lifecycleContent(generation, LifecycleState.PREPARED));
            writeUtf8Forced(lifecyclePath, lifecycleContent(generation, LifecycleState.PREPARED));
            Path rewrite = path.resolveSibling(path.getFileName() + ".rewrite");
            writeUtf8Forced(rewrite, checkpointContent);
            moveIntoPlace(rewrite, path);
            writeUtf8Forced(lifecyclePath, lifecycleContent(generation, LifecycleState.COMPLETED));
            Files.deleteIfExists(pendingPath);
            forceParentDirectoryIfSupported(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write inherited MVCC checkpoint: " + path, e);
        }
    }

    public void delete() throws IOException {
        if (enabled()) {
            Files.deleteIfExists(path);
            Files.deleteIfExists(pendingPath);
            Files.deleteIfExists(lifecyclePath);
        }
    }

    private boolean hasInterruptedLifecycle() {
        if (pendingPath != null && Files.exists(pendingPath)) {
            return true;
        }
        if (lifecyclePath == null || !Files.exists(lifecyclePath)) {
            return false;
        }
        try {
            Properties properties = readProperties(lifecyclePath);
            return !LifecycleState.COMPLETED.name().equals(properties.getProperty("state"));
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    private String checkpointContent(
            long generation,
            Path pageFile,
            Path rowDirectoryFile,
            Path pageMutationLogFile,
            Path writeAheadLogFile,
            Collection<MvccRowDirectoryStore.RowHeadRecord> heads,
            long physicalVersionCount,
            long logicalRowCount,
            long nextRowId) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("magic", MAGIC);
        properties.setProperty("version", VERSION);
        properties.setProperty("storageId", storageId);
        properties.setProperty("generation", Long.toString(generation));
        properties.setProperty("completed", Boolean.TRUE.toString());
        properties.setProperty("segment", storageSegment(storageId));
        properties.setProperty("container", storageContainer(storageId));
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
        return encodeProperties(properties);
    }

    private String lifecycleContent(long generation, LifecycleState state) {
        Properties properties = new Properties();
        properties.setProperty("magic", LIFECYCLE_MAGIC);
        properties.setProperty("version", "1");
        properties.setProperty("storageId", storageId);
        properties.setProperty("generation", Long.toString(generation));
        properties.setProperty("state", state.name());
        properties.setProperty("timestamp", Instant.now().toString());
        return encodeProperties(properties);
    }

    private static String encodeProperties(Properties properties) {
        return properties.stringPropertyNames().stream()
                .sorted()
                .map(name -> name + "=" + properties.getProperty(name))
                .collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator()));
    }

    private long nextGeneration() throws IOException {
        if (!Files.exists(path)) {
            return 1L;
        }
        try {
            return Math.max(0L, Long.parseLong(readCheckpoint().properties.getProperty("generation", "0"))) + 1L;
        } catch (RuntimeException e) {
            return 1L;
        }
    }

    private Checkpoint readCheckpoint() throws IOException {
        return new Checkpoint(readProperties(path));
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
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

    private static void writeUtf8Forced(Path file, String content) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(content, "content");
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                atomicMoveFailure.addSuppressed(fallbackFailure);
                throw atomicMoveFailure;
            }
        }
        forceParentDirectoryIfSupported(target);
    }

    private static void forceParentDirectoryIfSupported(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // Some platforms do not support forcing directories. File data has already been forced.
        }
    }

    private static String storageSegment(String storageId) {
        ConglomerateStorageId parsed = ConglomerateStorageId.parse(storageId);
        return parsed == null ? storageId : parsed.segment();
    }

    private static String storageContainer(String storageId) {
        ConglomerateStorageId parsed = ConglomerateStorageId.parse(storageId);
        return parsed == null ? storageId : parsed.container();
    }

    private record ConglomerateStorageId(String segment, String container) {
        private static ConglomerateStorageId parse(String storageId) {
            if (storageId == null || !storageId.startsWith("conglomerate-")) {
                return null;
            }
            String remainder = storageId.substring("conglomerate-".length());
            int separator = remainder.indexOf('-');
            if (separator <= 0 || separator == remainder.length() - 1) {
                return null;
            }
            return new ConglomerateStorageId(
                    remainder.substring(0, separator),
                    remainder.substring(separator + 1));
        }
    }

    public enum Status {
        DISABLED,
        ABSENT,
        WRITTEN,
        VALID,
        FALLBACK,
        INCOMPLETE
    }

    private enum LifecycleState {
        PREPARED,
        COMPLETED
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
            String storedVersion = properties.getProperty("version");
            if (!VERSION.equals(storedVersion) && !"1".equals(storedVersion)) {
                throw new IllegalStateException("Inherited MVCC checkpoint mismatch for version: expected "
                        + VERSION + " or legacy 1 but found " + storedVersion);
            }
            require("storageId", storageId);
            require("segment", storageSegment(storageId));
            require("container", storageContainer(storageId));
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
