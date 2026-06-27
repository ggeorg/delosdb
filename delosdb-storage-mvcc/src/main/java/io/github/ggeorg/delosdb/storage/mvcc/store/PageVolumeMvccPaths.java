package io.github.ggeorg.delosdb.storage.mvcc.store;

import java.nio.file.Path;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;

/** Shared page-volume file naming for inherited MVCC storage state. */
public final class PageVolumeMvccPaths {
    private PageVolumeMvccPaths() {
    }

    public static String conglomerateStorageId(long segmentId, long containerId) {
        return "conglomerate-" + segmentId + "-" + containerId;
    }

    public static Path inheritedStoreDirectory(Path databaseDirectory) {
        if (databaseDirectory == null) {
            return null;
        }
        return databaseDirectory
                .resolve(DelosMvccStorageProvider.DATABASE_STORAGE_DIRECTORY_NAME)
                .resolve("inherited-store");
    }

    public static Path pageFile(Path databaseDirectory, String storageId) {
        Path directory = inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve(requireStorageId(storageId) + ".pages");
    }

    public static Path pageMutationLogFileFor(Path pageFile) {
        Objects.requireNonNull(pageFile, "pageFile");
        return pageFile.resolveSibling(pageFile.getFileName() + ".pagemut");
    }

    public static Path writeAheadLogFile(Path databaseDirectory, String storageId) {
        Path directory = inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve(requireStorageId(storageId) + ".wal");
    }

    public static Path checkpointFile(Path databaseDirectory, String storageId) {
        Path directory = inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve(requireStorageId(storageId) + ".checkpoint");
    }

    private static String requireStorageId(String storageId) {
        String id = Objects.requireNonNull(storageId, "storageId");
        if (id.isBlank() || id.contains("/") || id.contains("\\\\")) {
            throw new IllegalArgumentException("Invalid MVCC storage id: " + storageId);
        }
        return id;
    }
}
