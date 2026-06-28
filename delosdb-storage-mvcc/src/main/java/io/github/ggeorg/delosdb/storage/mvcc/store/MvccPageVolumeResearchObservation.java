package io.github.ggeorg.delosdb.storage.mvcc.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Read-only research observation of the page-volume MVCC state-store boundary.
 *
 * <p>This diagnostic object records facts already exposed by {@link PageVolumeMvccStateStore}: file
 * locations, checkpoint validation state, write-ahead-log file presence, durable-state presence,
 * and row/version counters. It does not parse page, checkpoint, or WAL files, does not expose a WAL
 * replay position, and does not change Derby-compatible SQL/JDBC behavior.</p>
 */
public record MvccPageVolumeResearchObservation(
        String subject,
        boolean enabled,
        boolean durableState,
        String pageFile,
        String rowDirectoryFile,
        String pageMutationLogFile,
        String writeAheadLogFile,
        String writeAheadLogState,
        String checkpointFile,
        String checkpointState,
        int logicalRows,
        int physicalVersions,
        long nextInheritedRowId) {
    private static final String NOT_OBSERVED = "NOT_OBSERVED";
    private static final String PRESENT = "PRESENT";
    private static final String ABSENT = "ABSENT";

    public MvccPageVolumeResearchObservation {
        subject = normalize(subject);
        pageFile = normalize(pageFile);
        rowDirectoryFile = normalize(rowDirectoryFile);
        pageMutationLogFile = normalize(pageMutationLogFile);
        writeAheadLogFile = normalize(writeAheadLogFile);
        writeAheadLogState = normalize(writeAheadLogState);
        checkpointFile = normalize(checkpointFile);
        checkpointState = normalize(checkpointState);
    }

    public static MvccPageVolumeResearchObservation capture(
            String subject,
            PageVolumeMvccStateStore<?> stateStore) {
        Objects.requireNonNull(stateStore, "stateStore");

        return new MvccPageVolumeResearchObservation(
                subject,
                stateStore.enabled(),
                stateStore.hasDurableState(),
                path(stateStore.pageFile()),
                path(stateStore.rowDirectoryFile()),
                path(stateStore.pageMutationLogFile()),
                path(stateStore.writeAheadLogFile()),
                fileState(stateStore.writeAheadLogFile()),
                path(stateStore.checkpointFile()),
                stateStore.checkpointStatus(),
                stateStore.logicalRowCount(),
                stateStore.physicalVersionCount(),
                stateStore.nextInheritedRowId());
    }

    public String format() {
        return new StringBuilder()
                .append("subject: ").append(subject).append(System.lineSeparator())
                .append("enabled: ").append(enabled).append(System.lineSeparator())
                .append("durable state: ").append(durableState).append(System.lineSeparator())
                .append("page file: ").append(pageFile).append(System.lineSeparator())
                .append("row-directory file: ").append(rowDirectoryFile).append(System.lineSeparator())
                .append("page mutation log file: ").append(pageMutationLogFile).append(System.lineSeparator())
                .append("write-ahead log file: ").append(writeAheadLogFile).append(System.lineSeparator())
                .append("write-ahead log state: ").append(writeAheadLogState).append(System.lineSeparator())
                .append("checkpoint file: ").append(checkpointFile).append(System.lineSeparator())
                .append("checkpoint state: ").append(checkpointState).append(System.lineSeparator())
                .append("logical rows: ").append(logicalRows).append(System.lineSeparator())
                .append("physical versions: ").append(physicalVersions).append(System.lineSeparator())
                .append("next inherited row id: ").append(nextInheritedRowId)
                .toString();
    }

    private static String path(Path path) {
        return path == null ? NOT_OBSERVED : path.toString();
    }

    private static String fileState(Path path) {
        if (path == null) {
            return NOT_OBSERVED;
        }
        return Files.exists(path) ? PRESENT : ABSENT;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return NOT_OBSERVED;
        }
        return value;
    }
}
