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
import org.apache.derby.io.StorageFile;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
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

    private static final String DIGEST_ALGORITHM = "SHA-256";

    interface FileOperations {
        boolean exists(File file);

        boolean exists(StorageFile file);

        boolean removeDirectory(File file);

        boolean deleteAll(StorageFile file);

        boolean copyDirectory(StorageFile from, File to) throws StandardException;

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
        if (files.exists(backupMvccDirectory)) {
            files.removeDirectory(backupMvccDirectory);
        }

        copyRecoveryConsistentSnapshot(sourceMvccDirectory, backupMvccDirectory);
        SidecarBackupManifest backupManifest = SidecarBackupManifest.from(backupMvccDirectory);
        writeBackupManifest(backupcopy, backupManifest);
    }


    /**
     * Copy a fuzzy MVCC snapshot with recovery journals captured after page and
     * metadata files. Page flushes obey WAL-before-flush, so a journal prefix
     * captured after the corresponding page files can replay the backup to a
     * valid committed state. Append-only journals are copied only up to the
     * length observed when each copy starts; concurrent appends therefore do
     * not make the backup fail or produce a partial trailing record.
     */
    private static void copyRecoveryConsistentSnapshot(File sourceDirectory, File targetDirectory)
            throws StandardException {
        try {
            Path sourceRoot = sourceDirectory.toPath();
            Path targetRoot = targetDirectory.toPath();
            Files.createDirectories(targetRoot);

            List<Path> ordinaryFiles = new ArrayList<>();
            List<Path> recoveryJournals = new ArrayList<>();
            try (var paths = Files.walk(sourceRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> !isTransientRewrite(path))
                        .forEach(path -> {
                            if (isRecoveryJournal(path)) {
                                recoveryJournals.add(path);
                            } else {
                                ordinaryFiles.add(path);
                            }
                        });
            }

            Comparator<Path> relativeOrder = Comparator.comparing(
                    path -> normalizeRelativePath(sourceRoot, path));
            ordinaryFiles.sort(relativeOrder);
            recoveryJournals.sort(Comparator
                    .comparingInt(DelosMvccBackupSidecarSupport::recoveryJournalRank)
                    .thenComparing(relativeOrder));

            for (Path source : ordinaryFiles) {
                copyStableFile(sourceRoot, source, targetRoot);
            }
            for (Path source : recoveryJournals) {
                copyAppendOnlyPrefix(sourceRoot, source, targetRoot);
            }
        } catch (IOException | SecurityException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    private static boolean isTransientRewrite(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".rewrite") || name.endsWith(".backup-copying");
    }

    private static boolean isRecoveryJournal(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".pagemut")
                || name.endsWith(".txoutcome")
                || name.endsWith(".recovery")
                || name.endsWith(".wal");
    }

    private static int recoveryJournalRank(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".pagemut")) {
            return 0;
        }
        if (name.endsWith(".txoutcome")) {
            return 1;
        }
        if (name.endsWith(".recovery")) {
            return 2;
        }
        return 3; // Main write-ahead log is the final recovery authority copied.
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

    private static void copyAppendOnlyPrefix(Path sourceRoot, Path source, Path targetRoot)
            throws IOException {
        Path target = targetRoot.resolve(sourceRoot.relativize(source));
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".backup-copying");
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(temporary,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            long snapshotLength = input.size();
            long position = 0L;
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (position < snapshotLength) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), snapshotLength - position));
                int read = input.read(buffer, position);
                if (read < 0) {
                    throw new IOException("MVCC recovery journal shrank during backup: " + source);
                }
                if (read == 0) {
                    Thread.onSpinWait();
                    continue;
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                position += read;
            }
            output.force(true);
        }
        try {
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
        StorageFile dbMvccDirectory = storageFactory.newStorageFile(STORAGE_DIRECTORY_NAME);

        if (files.exists(dbMvccDirectory) && !files.deleteAll(dbMvccDirectory)) {
            throw StandardException.newException(
                    SQLState.UNABLE_TO_COPY_FILE_FROM_BACKUP,
                    backupMvccDirectory,
                    dbMvccDirectory);
        }

        if (!files.exists(backupMvccDirectory)) {
            return;
        }

        verifyBackupManifest(backupPath, backupMvccDirectory);
        if (!files.copyDirectory(backupMvccDirectory, dbMvccDirectory)) {
            throw StandardException.newException(
                    SQLState.UNABLE_TO_COPY_FILE_FROM_BACKUP,
                    backupMvccDirectory,
                    dbMvccDirectory);
        }
    }

    private void writeBackupManifest(File backupcopy, SidecarBackupManifest manifest)
            throws StandardException {
        File manifestFile = new File(backupcopy, BACKUP_MANIFEST);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(manifestFile), StandardCharsets.UTF_8)) {
            writer.write("version=3\n");
            writer.write("copyMode=fuzzy-recovery-journals-last\n");
            writer.write("directory=" + STORAGE_DIRECTORY_NAME + "\n");
            writer.write("fileCount=" + manifest.fileCount() + "\n");
            writer.write("totalBytes=" + manifest.totalBytes() + "\n");
            writer.write("digest=" + manifest.digest() + "\n");
        } catch (IOException e) {
            throw StandardException.plainWrapException(e);
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
            long files = -1L;
            long bytes = -1L;
            String digest = null;
            try {
                for (String line : java.nio.file.Files.readAllLines(
                        manifestFile.toPath(), StandardCharsets.UTF_8)) {
                    if (line.startsWith("fileCount=")) {
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
            if (files < 0L || bytes < 0L) {
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
            return digest == null || digest.equals(actual.digest);
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
