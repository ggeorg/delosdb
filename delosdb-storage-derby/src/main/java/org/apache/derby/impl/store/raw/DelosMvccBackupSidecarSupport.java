/*

   DelosDB - MVCC backup sidecar helper for inherited Derby RawStore.

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

package org.apache.derby.impl.store.raw;

import org.apache.derby.io.StorageFactory;
import org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator;
import org.apache.derby.io.StorageFile;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DelosDB-owned helper for MVCC provider sidecars that participate in Derby's
 * inherited raw-store backup and restore flow.
 *
 * <p>The helper deliberately stays in the Derby storage module and depends only
 * on RawStore-level file operations. It must not depend on the MVCC module: the
 * raw store knows only that DelosDB provider state may live in a sibling
 * database directory named {@code delos_mvcc}.</p>
 */
final class DelosMvccBackupSidecarSupport {
    static final String STORAGE_DIRECTORY_NAME = "delos_mvcc";
    static final String BACKUP_MANIFEST = "delos_mvcc.BACKUP-MANIFEST";
    static final String BACKUP_IN_PROGRESS = "delos_mvcc.BACKUP-IN-PROGRESS";

    private static final String DIGEST_ALGORITHM = "SHA-256";

    interface FileOperations {
        boolean exists(File file);

        boolean exists(StorageFile file);

        boolean removeDirectory(File file);

        boolean deleteAll(StorageFile file);

        boolean copyDirectory(File from, StorageFile to);
    }

    private final StorageFactory storageFactory;
    private final FileOperations files;

    DelosMvccBackupSidecarSupport(StorageFactory storageFactory, FileOperations files) {
        this.storageFactory = storageFactory;
        this.files = files;
    }

    /**
     * Copy DelosDB MVCC provider-owned sidecars into Derby online backups.
     *
     * <p>Derby's inherited backup flow copies seg0, log, jars, and the backup
     * history explicitly. DelosDB MVCC sidecar state lives in a sibling
     * database directory named {@code delos_mvcc}, so it must be included here
     * or a backup of a database with delos_mvcc tables can restore with catalog
     * rows but missing provider-owned durable state.</p>
     */
    void backupSidecars(File backupcopy) throws StandardException {
        StorageFile mvccDirectory = storageFactory.newStorageFile(STORAGE_DIRECTORY_NAME);
        if (!files.exists(mvccDirectory)) {
            return;
        }

        File sourceMvccDirectory = new File(mvccDirectory.getPath());
        File backupMvccDirectory = new File(backupcopy, STORAGE_DIRECTORY_NAME);
        File inProgressMarker = new File(backupcopy, BACKUP_IN_PROGRESS);
        createBackupInProgressMarker(inProgressMarker);
        if (files.exists(backupMvccDirectory)) {
            files.removeDirectory(backupMvccDirectory);
        }

        try (DelosStorageBackupCoordinator.DatabaseLease coordinatorLease =
                     backupCoordinatorLease();
             DelosStorageBackupCoordinator.Guard ignored =
                     coordinatorLease.coordinator().enterBackupSnapshot()) {
            copyCoordinatedSnapshot(sourceMvccDirectory, backupMvccDirectory);
            SidecarBackupManifest backupManifest = SidecarBackupManifest.from(backupMvccDirectory);
            writeBackupManifest(backupcopy, backupManifest);
            deleteBackupInProgressMarker(inProgressMarker);
        }
    }


    /**
     * Copy one coordinated MVCC snapshot while durable mutations are excluded
     * by {@link DelosStorageBackupCoordinator}. Cross-subsystem page, outcome,
     * recovery, checkpoint, and WAL files therefore belong to one boundary.
     */
    private DelosStorageBackupCoordinator.DatabaseLease backupCoordinatorLease()
            throws StandardException {
        try {
            return DelosStorageBackupCoordinator.openDatabase(storageFactory.getCanonicalName());
        } catch (IOException | RuntimeException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    private static void copyCoordinatedSnapshot(File sourceDirectory, File targetDirectory)
            throws StandardException {
        try {
            Path sourceRoot = sourceDirectory.toPath();
            Path targetRoot = targetDirectory.toPath();
            Files.createDirectories(targetRoot);

            List<Path> durableFiles = new ArrayList<>();
            try (var paths = Files.walk(sourceRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> !isTransientRewrite(path))
                        .forEach(durableFiles::add);
            }
            durableFiles.sort(Comparator.comparing(
                    path -> normalizeRelativePath(sourceRoot, path)));
            for (Path source : durableFiles) {
                copyStableFile(sourceRoot, source, targetRoot);
            }
        } catch (IOException | SecurityException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    private static boolean isTransientRewrite(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".rewrite") || name.endsWith(".backup-copying");
    }

    private static void copyStableFile(Path sourceRoot, Path source, Path targetRoot)
            throws IOException {
        Path target = targetRoot.resolve(sourceRoot.relativize(source));
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".backup-copying");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            publishCopiedFile(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void publishCopiedFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                atomicMoveFailure.addSuppressed(fallbackFailure);
                throw atomicMoveFailure;
            }
        }
    }

    private static String normalizeRelativePath(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    /**
     * Restore DelosDB MVCC provider-owned sidecars alongside Derby data/log state.
     *
     * <p>If the backup contains sidecars, replace the target sidecar directory
     * with the backup copy. If the backup does not contain sidecars, remove any
     * target sidecar directory left over from the database being restored over;
     * otherwise stale provider state could survive a restore of a heap-only or
     * older backup.</p>
     */
    void restoreSidecarsFromBackup(String backupPath) throws StandardException {
        File backupMvccDirectory = new File(backupPath, STORAGE_DIRECTORY_NAME);
        File inProgressMarker = new File(backupPath, BACKUP_IN_PROGRESS);
        StorageFile dbMvccDirectory = storageFactory.newStorageFile(STORAGE_DIRECTORY_NAME);

        if (files.exists(inProgressMarker)) {
            throw StandardException.newException(
                    SQLState.UNABLE_TO_COPY_FILE_FROM_BACKUP,
                    inProgressMarker,
                    dbMvccDirectory);
        }

        if (files.exists(backupMvccDirectory)) {
            verifyBackupManifest(backupPath, backupMvccDirectory);
        }

        if (files.exists(dbMvccDirectory) && !files.deleteAll(dbMvccDirectory)) {
            throw StandardException.newException(
                    SQLState.UNABLE_TO_COPY_FILE_FROM_BACKUP,
                    backupMvccDirectory,
                    dbMvccDirectory);
        }

        if (!files.exists(backupMvccDirectory)) {
            return;
        }
        if (!files.copyDirectory(backupMvccDirectory, dbMvccDirectory)) {
            throw StandardException.newException(
                    SQLState.UNABLE_TO_COPY_FILE_FROM_BACKUP,
                    backupMvccDirectory,
                    dbMvccDirectory);
        }
    }


    private static void createBackupInProgressMarker(File marker) throws StandardException {
        writeDurableFile(marker.toPath(), "delos-mvcc-backup-in-progress\n");
    }

    private static void deleteBackupInProgressMarker(File marker) throws StandardException {
        try {
            Path markerPath = marker.toPath();
            Files.delete(markerPath);
            forceDirectoryIfSupported(markerPath.getParent());
        } catch (IOException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    private void writeBackupManifest(File backupcopy, SidecarBackupManifest manifest)
            throws StandardException {
        String contents = "version=3\n"
                + "copyMode=coordinated-durable-mutation-boundary\n"
                + "directory=" + STORAGE_DIRECTORY_NAME + "\n"
                + "fileCount=" + manifest.fileCount() + "\n"
                + "totalBytes=" + manifest.totalBytes() + "\n"
                + "digest=" + manifest.digest() + "\n";
        writeDurableFile(new File(backupcopy, BACKUP_MANIFEST).toPath(), contents);
    }

    private static void writeDurableFile(Path target, String contents) throws StandardException {
        Path temporary = target.resolveSibling(target.getFileName() + ".rewrite");
        try {
            Files.createDirectories(target.getParent());
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(contents);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            publishCopiedFile(temporary, target);
            forceDirectoryIfSupported(target.getParent());
        } catch (IOException e) {
            throw StandardException.plainWrapException(e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The published target is authoritative; stale rewrite files are ignored by backup.
            }
        }
    }

    private static void forceDirectoryIfSupported(Path directory) throws IOException {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException | IOException e) {
            // Some file systems do not expose directory fsync through FileChannel.
        }
    }

    private void verifyBackupManifest(String backupPath, File backupMvccDirectory)
            throws StandardException {
        File manifestFile = new File(backupPath, BACKUP_MANIFEST);
        if (!files.exists(manifestFile)) {
            // Legacy DelosDB sidecar backups created before the manifest proof
            // are still restorable; all new backups write and verify this file.
            return;
        }
        SidecarBackupManifest actual = SidecarBackupManifest.from(backupMvccDirectory);
        SidecarBackupManifest expected = SidecarBackupManifest.read(manifestFile);
        if (!expected.matches(actual)) {
            throw StandardException.newException(
                    SQLState.UNABLE_TO_COPY_FILE_FROM_BACKUP,
                    manifestFile,
                    backupMvccDirectory);
        }
    }

    private record SidecarBackupManifest(long fileCount, long totalBytes, String digest) {
        private static SidecarBackupManifest from(File directory) throws StandardException {
            if (directory == null || !directory.exists()) {
                return empty();
            }
            try {
                MessageDigest messageDigest = MessageDigest.getInstance(DIGEST_ALGORITHM);
                java.nio.file.Path root = directory.toPath();
                List<java.nio.file.Path> files = new ArrayList<>();
                try (var paths = java.nio.file.Files.walk(root)) {
                    paths.filter(java.nio.file.Files::isRegularFile)
                            .forEach(files::add);
                }
                files.sort(Comparator.comparing(path -> normalizeRelativePath(root, path)));

                long fileCount = 0L;
                long totalBytes = 0L;
                byte[] buffer = new byte[8192];
                for (java.nio.file.Path file : files) {
                    String relativeName = normalizeRelativePath(root, file);
                    byte[] relativeBytes = relativeName.getBytes(StandardCharsets.UTF_8);
                    messageDigest.update(relativeBytes);
                    messageDigest.update((byte) 0);

                    long size = java.nio.file.Files.size(file);
                    totalBytes += size;
                    updateLong(messageDigest, size);
                    messageDigest.update((byte) 0);

                    try (java.io.InputStream input = java.nio.file.Files.newInputStream(file)) {
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            messageDigest.update(buffer, 0, read);
                        }
                    }
                    messageDigest.update((byte) 0);
                    fileCount++;
                }
                return new SidecarBackupManifest(fileCount, totalBytes, hex(messageDigest.digest()));
            } catch (IOException | SecurityException | NoSuchAlgorithmException e) {
                throw StandardException.plainWrapException(e);
            }
        }

        private static SidecarBackupManifest read(File manifestFile) throws StandardException {
            String version = null;
            String copyMode = null;
            String directory = null;
            long files = -1L;
            long bytes = -1L;
            String digest = null;
            try {
                for (String line : Files.readAllLines(
                        manifestFile.toPath(), StandardCharsets.UTF_8)) {
                    if (line.startsWith("version=")) {
                        version = line.substring("version=".length());
                    } else if (line.startsWith("copyMode=")) {
                        copyMode = line.substring("copyMode=".length());
                    } else if (line.startsWith("directory=")) {
                        directory = line.substring("directory=".length());
                    } else if (line.startsWith("fileCount=")) {
                        files = Long.parseLong(line.substring("fileCount=".length()));
                    } else if (line.startsWith("totalBytes=")) {
                        bytes = Long.parseLong(line.substring("totalBytes=".length()));
                    } else if (line.startsWith("digest=")) {
                        digest = line.substring("digest=".length());
                    }
                }
            } catch (IOException | NumberFormatException e) {
                throw StandardException.plainWrapException(e);
            }

            boolean supportedVersion = "2".equals(version) || "3".equals(version);
            boolean validCopyMode = !"3".equals(version)
                    || "coordinated-durable-mutation-boundary".equals(copyMode);
            if (!supportedVersion
                    || !validCopyMode
                    || !STORAGE_DIRECTORY_NAME.equals(directory)
                    || files < 0L
                    || bytes < 0L
                    || digest == null
                    || digest.isBlank()) {
                throw StandardException.newException(
                        SQLState.UNABLE_TO_COPY_FILE_FROM_BACKUP,
                        manifestFile,
                        STORAGE_DIRECTORY_NAME);
            }
            return new SidecarBackupManifest(files, bytes, digest);
        }

        private static SidecarBackupManifest empty() throws StandardException {
            try {
                MessageDigest messageDigest = MessageDigest.getInstance(DIGEST_ALGORITHM);
                return new SidecarBackupManifest(0L, 0L, hex(messageDigest.digest()));
            } catch (NoSuchAlgorithmException e) {
                throw StandardException.plainWrapException(e);
            }
        }

        private boolean matches(SidecarBackupManifest actual) {
            if (actual == null) {
                return false;
            }
            if (fileCount != actual.fileCount || totalBytes != actual.totalBytes) {
                return false;
            }
            return digest.equals(actual.digest);
        }

        private static void updateLong(MessageDigest digest, long value) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                digest.update((byte) (value >>> shift));
            }
        }

        private static String hex(byte[] bytes) {
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                int unsigned = value & 0xff;
                if (unsigned < 0x10) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(unsigned));
            }
            return builder.toString();
        }

        SidecarBackupManifest {
            if (fileCount < 0L || totalBytes < 0L) {
                throw new IllegalArgumentException("DelosDB MVCC backup manifest counts must not be negative");
            }
            if (digest != null && digest.isBlank()) {
                throw new IllegalArgumentException("DelosDB MVCC backup manifest digest must not be blank");
            }
        }
    }
}
