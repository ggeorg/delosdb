package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

/**
 * Read-only research snapshot of the native MVCC kernel.
 *
 * <p>This class is diagnostic-only. It does not subscribe to engine tracing, does not mutate the
 * transaction table or row store, and does not define a public cross-module observability API. It
 * records the MVCC facts that already exist in the native storage subsystem so Phase 24 can make
 * snapshot, visibility, vacuum-horizon, and version-count concepts visible before adding broader
 * SQL-engine integration.</p>
 */
public record MvccResearchObservation(
        String subject,
        long ownerTransactionId,
        long visibleThroughCommitSequence,
        String visibleThroughCommandSequence,
        int activeAtCapture,
        int visibleRows,
        int logicalRows,
        int physicalVersions,
        int deadVersionsEstimate,
        long newestCommitSequence,
        long oldestRetainedVisibleThrough,
        int activeTransactions,
        int retainedSnapshots,
        long compactedTransactionIdThrough,
        String walPosition,
        String checkpointState) {
    private static final String NOT_OBSERVED = "NOT_OBSERVED";

    public MvccResearchObservation {
        subject = normalize(subject);
        visibleThroughCommandSequence = normalize(visibleThroughCommandSequence);
        walPosition = normalize(walPosition);
        checkpointState = normalize(checkpointState);
    }

    public static <K, V> MvccResearchObservation capture(
            String subject,
            MvccTable<K, V> table,
            MvccTransactionManager transactionManager,
            MvccSnapshot snapshot) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(transactionManager, "transactionManager");
        Objects.requireNonNull(snapshot, "snapshot");

        MvccCommitSequence oldestRetainedVisibleThrough =
                transactionManager.oldestRetainedVisibleThrough();
        return new MvccResearchObservation(
                subject,
                snapshot.owner().value(),
                snapshot.visibleThrough().value(),
                snapshot.visibleThroughCommand().toString(),
                snapshot.activeAtCapture().size(),
                table.visibleRowCount(snapshot, transactionManager),
                table.logicalRowCount(),
                table.physicalVersionCount(),
                table.deadVersionEstimate(oldestRetainedVisibleThrough, transactionManager),
                transactionManager.newestCommitSequence().value(),
                oldestRetainedVisibleThrough.value(),
                transactionManager.activeTransactionCount(),
                transactionManager.retainedSnapshotCount(),
                transactionManager.compactedTransactionIdThrough().value(),
                NOT_OBSERVED,
                NOT_OBSERVED);
    }

    public static <K, V> MvccResearchObservation capture(
            String subject,
            MvccTable<K, V> table,
            MvccTransactionManager transactionManager,
            MvccStatementSnapshot statementSnapshot) {
        Objects.requireNonNull(statementSnapshot, "statementSnapshot");
        return capture(subject, table, transactionManager, statementSnapshot.snapshot());
    }

    public String format() {
        return new StringBuilder()
                .append("subject: ").append(subject).append(System.lineSeparator())
                .append("owner transaction id: ").append(ownerTransactionId)
                .append(System.lineSeparator())
                .append("visible through commit sequence: ").append(visibleThroughCommitSequence)
                .append(System.lineSeparator())
                .append("visible through command sequence: ").append(visibleThroughCommandSequence)
                .append(System.lineSeparator())
                .append("active at capture: ").append(activeAtCapture).append(System.lineSeparator())
                .append("visible rows: ").append(visibleRows).append(System.lineSeparator())
                .append("logical rows: ").append(logicalRows).append(System.lineSeparator())
                .append("physical versions: ").append(physicalVersions).append(System.lineSeparator())
                .append("dead versions estimate: ").append(deadVersionsEstimate)
                .append(System.lineSeparator())
                .append("newest commit sequence: ").append(newestCommitSequence)
                .append(System.lineSeparator())
                .append("oldest retained visible through: ").append(oldestRetainedVisibleThrough)
                .append(System.lineSeparator())
                .append("active transactions: ").append(activeTransactions).append(System.lineSeparator())
                .append("retained snapshots: ").append(retainedSnapshots).append(System.lineSeparator())
                .append("compacted transaction id through: ").append(compactedTransactionIdThrough)
                .append(System.lineSeparator())
                .append("wal position: ").append(walPosition).append(System.lineSeparator())
                .append("checkpoint state: ").append(checkpointState)
                .toString();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return NOT_OBSERVED;
        }
        return value;
    }
}
