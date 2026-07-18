/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccConglomerateLifecycleFiles

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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.derby.iapi.store.types.DelosMvccConglomerateLifecycle;

/** Durable external-file lifecycle helpers for transactional MVCC DDL. */
final class MvccConglomerateLifecycleFiles {
    private static final Pattern CREATE_MARKER = Pattern.compile(
            "create-(\\d+)-(\\d+)\\.(pending|committed)");

    private MvccConglomerateLifecycleFiles() {
    }

    static void stageCreate(
            Path databaseDirectory,
            DelosMvccConglomerateLifecycle lifecycle) {
        requireCreate(lifecycle);
        writeMarker(lifecycle.pendingCreateMarker(databaseDirectory), "PENDING", lifecycle);
    }

    static void completeCreate(
            Path databaseDirectory,
            DelosMvccConglomerateLifecycle lifecycle) {
        requireCreate(lifecycle);
        Path committed = lifecycle.committedCreateMarker(databaseDirectory);
        writeMarker(committed, "COMMITTED", lifecycle);
        deleteIfExists(lifecycle.pendingCreateMarker(databaseDirectory));
        deleteIfExists(committed);
    }

    static void abortCreate(
            Path databaseDirectory,
            DelosMvccConglomerateLifecycle lifecycle) {
        requireCreate(lifecycle);
        deleteStateFiles(databaseDirectory, lifecycle);
        deleteIfExists(lifecycle.pendingCreateMarker(databaseDirectory));
        deleteIfExists(lifecycle.committedCreateMarker(databaseDirectory));
    }

    static void completeDrop(
            Path databaseDirectory,
            DelosMvccConglomerateLifecycle lifecycle) {
        requireDrop(lifecycle);
        deleteStateFiles(databaseDirectory, lifecycle);
    }

    /**
     * Resolve CREATE markers before provider state opens.
     *
     * <p>A committed marker wins over a pending marker and preserves provider
     * files. A lone pending marker has no durable raw-store commit and is an
     * orphan which must be retired.</p>
     */
    static void recoverInterruptedCreates(Path databaseDirectory) {
        Path lifecycleDirectory = databaseDirectory
                .resolve(DelosMvccConglomerateLifecycle.PROVIDER_DIRECTORY)
                .resolve(DelosMvccConglomerateLifecycle.LIFECYCLE_DIRECTORY);
        if (!Files.isDirectory(lifecycleDirectory)) {
            return;
        }

        Map<CreateIdentity, MarkerState> markers = new HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(lifecycleDirectory)) {
            for (Path marker : stream) {
                Matcher matcher = CREATE_MARKER.matcher(marker.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                CreateIdentity identity = new CreateIdentity(
                        Long.parseLong(matcher.group(1)),
                        Long.parseLong(matcher.group(2)));
                MarkerState state = markers.computeIfAbsent(identity, ignored -> new MarkerState());
                if ("committed".equals(matcher.group(3))) {
                    state.committed = marker;
                } else {
                    state.pending = marker;
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Unable to inspect delos_mvcc CREATE lifecycle markers", failure);
        }

        for (Map.Entry<CreateIdentity, MarkerState> entry : markers.entrySet()) {
            CreateIdentity identity = entry.getKey();
            MarkerState state = entry.getValue();
            DelosMvccConglomerateLifecycle lifecycle = new DelosMvccConglomerateLifecycle(
                    DelosMvccConglomerateLifecycle.Operation.CREATE,
                    identity.segmentId(), identity.containerId());
            if (state.committed == null) {
                deleteStateFiles(databaseDirectory, lifecycle);
            }
            deleteIfExists(state.pending);
            deleteIfExists(state.committed);
        }
    }

    private static void deleteStateFiles(
            Path databaseDirectory,
            DelosMvccConglomerateLifecycle lifecycle) {
        Path directory = lifecycle.inheritedStoreDirectory(databaseDirectory);
        if (!Files.isDirectory(directory)) {
            return;
        }
        String glob = lifecycle.storageId() + ".*";
        boolean deletedState = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, glob)) {
            for (Path file : stream) {
                deletedState |= Files.deleteIfExists(file);
            }
            if (deletedState) {
                forceDirectoryIfSupported(directory);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Unable to retire delos_mvcc state for " + lifecycle.storageId(), failure);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                directory, "conglomerate-*")) {
            if (!stream.iterator().hasNext()) {
                deleteIfExists(directory.resolve("database-transactions.txstatus"));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Unable to compact final delos_mvcc database decision state", failure);
        }
    }

    private static void writeMarker(
            Path marker,
            String state,
            DelosMvccConglomerateLifecycle lifecycle) {
        byte[] payload = (state
                + "\t" + lifecycle.segmentId()
                + "\t" + lifecycle.containerId()
                + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(marker.getParent());
            try (FileChannel channel = FileChannel.open(
                    marker,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(payload);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            forceDirectoryIfSupported(marker.getParent());
            forceDirectoryIfSupported(marker.getParent().getParent());
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Unable to persist delos_mvcc lifecycle marker " + marker, failure);
        }
    }

    private static void deleteIfExists(Path file) {
        if (file == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(file)) {
                forceDirectoryIfSupported(file.getParent());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Unable to remove delos_mvcc lifecycle file " + file, failure);
        }
    }

    private static void forceDirectoryIfSupported(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Some file systems do not expose directory fsync through FileChannel.
        }
    }

    private static void requireCreate(DelosMvccConglomerateLifecycle lifecycle) {
        if (lifecycle.operation() != DelosMvccConglomerateLifecycle.Operation.CREATE) {
            throw new IllegalArgumentException("Expected a delos_mvcc CREATE lifecycle");
        }
    }

    private static void requireDrop(DelosMvccConglomerateLifecycle lifecycle) {
        if (lifecycle.operation() != DelosMvccConglomerateLifecycle.Operation.DROP) {
            throw new IllegalArgumentException("Expected a delos_mvcc DROP lifecycle");
        }
    }

    private record CreateIdentity(long segmentId, long containerId) {
    }

    private static final class MarkerState {
        private Path pending;
        private Path committed;
    }
}
