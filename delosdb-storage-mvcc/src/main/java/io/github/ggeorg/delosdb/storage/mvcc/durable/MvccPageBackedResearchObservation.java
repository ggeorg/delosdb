package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;

/**
 * Read-only research observation of the page-backed MVCC prototype.
 *
 * <p>This diagnostic object records facts already exposed by {@link PageBackedMvccTable}: page
 * count, row-directory heads, logical rows, physical versions, and snapshot-visible rows. It does
 * not parse page files, does not define a public cross-module observability API, and does not claim
 * a Derby-compatible SQL storage-route change. Log and checkpoint fields are deliberately limited to
 * what this prototype can honestly expose.</p>
 */
public record MvccPageBackedResearchObservation(
        String subject,
        String pageFile,
        String rowDirectoryFile,
        String mutationLogFile,
        long pageCount,
        int rowDirectoryHeads,
        int logicalRows,
        int physicalVersions,
        int visibleRows,
        String mutationLogState,
        String checkpointState) {
    private static final String NOT_OBSERVED = "NOT_OBSERVED";
    private static final String PRESENT = "PRESENT";

    public MvccPageBackedResearchObservation {
        subject = normalize(subject);
        pageFile = normalize(pageFile);
        rowDirectoryFile = normalize(rowDirectoryFile);
        mutationLogFile = normalize(mutationLogFile);
        mutationLogState = normalize(mutationLogState);
        checkpointState = normalize(checkpointState);
    }

    public static MvccPageBackedResearchObservation capture(
            String subject,
            Path pageFile,
            Path mutationLogFile,
            PageBackedMvccTable table,
            MvccCommitSequence snapshotSequence) throws IOException {
        Objects.requireNonNull(pageFile, "pageFile");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(snapshotSequence, "snapshotSequence");

        return new MvccPageBackedResearchObservation(
                subject,
                pageFile.toString(),
                table.rowDirectoryPath().toString(),
                mutationLogFile == null ? NOT_OBSERVED : mutationLogFile.toString(),
                table.pageCount(),
                table.durableRowDirectoryHeads().size(),
                table.logicalRowCount(),
                table.physicalVersionCount(),
                table.visibleRows(snapshotSequence).size(),
                mutationLogState(mutationLogFile),
                NOT_OBSERVED);
    }

    public String format() {
        return new StringBuilder()
                .append("subject: ").append(subject).append(System.lineSeparator())
                .append("page file: ").append(pageFile).append(System.lineSeparator())
                .append("row-directory file: ").append(rowDirectoryFile).append(System.lineSeparator())
                .append("mutation log file: ").append(mutationLogFile).append(System.lineSeparator())
                .append("page count: ").append(pageCount).append(System.lineSeparator())
                .append("row-directory heads: ").append(rowDirectoryHeads).append(System.lineSeparator())
                .append("logical rows: ").append(logicalRows).append(System.lineSeparator())
                .append("physical versions: ").append(physicalVersions).append(System.lineSeparator())
                .append("visible rows: ").append(visibleRows).append(System.lineSeparator())
                .append("mutation log state: ").append(mutationLogState).append(System.lineSeparator())
                .append("checkpoint state: ").append(checkpointState)
                .toString();
    }

    private static String mutationLogState(Path mutationLogFile) {
        if (mutationLogFile == null) {
            return NOT_OBSERVED;
        }
        return Files.exists(mutationLogFile) ? PRESENT : NOT_OBSERVED;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return NOT_OBSERVED;
        }
        return value;
    }
}
