package io.github.ggeorg.delosdb.storage.mvcc.store;

import java.nio.file.Path;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.MvccStorageNames;

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
                .resolve(MvccStorageNames.DATABASE_STORAGE_DIRECTORY_NAME)
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


    public static Path transactionOutcomeLogFileFor(Path pageFile) {
        Objects.requireNonNull(pageFile, "pageFile");
        return pageFile.resolveSibling(pageFile.getFileName() + ".txoutcome");
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

    public static Path subsystemRecoveryRecordsFile(Path databaseDirectory, String storageId) {
        Path directory = inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve(requireStorageId(storageId) + ".recovery");
    }

    static boolean isMissingStorageId(String storageId) {
        return storageId == null || storageId.isBlank();
    }

    static boolean isUsableStorageId(String storageId) {
        return !isMissingStorageId(storageId) && !hasUnsafeStorageIdCharacter(storageId);
    }

    private static String requireStorageId(String storageId) {
        String id = Objects.requireNonNull(storageId, "storageId");
        if (id.isBlank() || hasUnsafeStorageIdCharacter(id)) {
            throw new IllegalArgumentException("Invalid MVCC storage id: " + storageId);
        }
        return id;
    }

    private static boolean hasUnsafeStorageIdCharacter(String storageId) {
        for (int index = 0; index < storageId.length(); index++) {
            char ch = storageId.charAt(index);
            if (ch == '/' || ch == '\\' || Character.isISOControl(ch)) {
                return true;
            }
        }
        return false;
    }
}
