package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatus;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusRecord;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccDurableFiles;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccPageMutationLog;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccTransactionOutcomeLog;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccPaths;

import org.apache.derby.iapi.store.types.DelosDatabaseCommitDecision;

/**
 * Bounded retention for raw-store decision markers and the database MVCC
 * transaction-status journal.
 *
 * <p>A raw-store marker is needed only until the same committed decision has
 * been forced into the database MVCC transaction-status journal. The journal
 * is compacted to unresolved prepared-mutation correlations plus exact
 * transaction-id and commit-sequence watermarks.</p>
 */
final class MvccDatabaseDecisionRetention {
    static final long DEFAULT_COMPACTION_THRESHOLD_BYTES = 1024L * 1024L;

    private static final String PAGE_MUTATION_SUFFIX = ".pagemut";

    private final Path databaseDirectory;
    private final long compactionThresholdBytes;

    MvccDatabaseDecisionRetention(Path databaseDirectory) {
        this(databaseDirectory, DEFAULT_COMPACTION_THRESHOLD_BYTES);
    }

    MvccDatabaseDecisionRetention(
            Path databaseDirectory,
            long compactionThresholdBytes) {
        if (compactionThresholdBytes <= 0L) {
            throw new IllegalArgumentException("compactionThresholdBytes must be positive");
        }
        this.databaseDirectory = databaseDirectory == null
                ? null
                : databaseDirectory.toAbsolutePath().normalize();
        this.compactionThresholdBytes = compactionThresholdBytes;
    }

    void reconcileOnOpen(MvccTransactionStatusStore decisionStore) {
        Objects.requireNonNull(decisionStore, "decisionStore");
        if (databaseDirectory == null || !decisionStore.isEnabled()) {
            return;
        }

        Map<MvccTransactionId, MvccTransactionStatusRecord> recovered =
                decisionStore.recoverStatuses();
        List<DelosDatabaseCommitDecision> markers =
                new ArrayList<>(DelosDatabaseCommitDecision
                        .recoverCommitted(databaseDirectory)
                        .values());
        markers.sort(Comparator.comparingLong(DelosDatabaseCommitDecision::commitSequence));

        List<MvccTransactionStatusStore.CommittedStatus> missing = new ArrayList<>();
        for (DelosDatabaseCommitDecision marker : markers) {
            MvccTransactionId transactionId = new MvccTransactionId(marker.transactionId());
            MvccTransactionStatusRecord status = recovered.get(transactionId);
            if (status == null || status.status() == MvccTransactionStatus.RECOVERY_PENDING) {
                missing.add(new MvccTransactionStatusStore.CommittedStatus(
                        transactionId,
                        new io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence(
                                marker.commitSequence())));
                continue;
            }
            if (status.status() != MvccTransactionStatus.COMMITTED
                    || status.commitSequence().value() != marker.commitSequence()) {
                throw new IllegalStateException(
                        "Conflicting retained database decision for transaction "
                                + marker.transactionId() + ": status=" + status
                                + ", marker=" + marker);
            }
        }
        if (!missing.isEmpty()) {
            decisionStore.recordCommittedBatch(missing);
        }

        Map<MvccTransactionId, MvccTransactionStatusRecord> mirrored =
                decisionStore.recoverStatuses();
        for (DelosDatabaseCommitDecision marker : markers) {
            MvccTransactionStatusRecord status = mirrored.get(
                    new MvccTransactionId(marker.transactionId()));
            if (status != null
                    && status.status() == MvccTransactionStatus.COMMITTED
                    && status.commitSequence().value() == marker.commitSequence()) {
                retireMarker(marker);
            }
        }
        compactIfNeeded(decisionStore, true);
    }

    void decisionMirrored(
            MvccTransactionStatusStore decisionStore,
            DelosDatabaseCommitDecision decision) {
        Objects.requireNonNull(decisionStore, "decisionStore");
        Objects.requireNonNull(decision, "decision");
        retireMarker(decision);
        compactIfNeeded(decisionStore, false);
    }

    void compactIfNeeded(
            MvccTransactionStatusStore decisionStore,
            boolean openingDatabase) {
        Objects.requireNonNull(decisionStore, "decisionStore");
        if (databaseDirectory == null || !decisionStore.isEnabled()) {
            return;
        }
        if (!openingDatabase && decisionStore.sizeBytes() < compactionThresholdBytes) {
            return;
        }
        decisionStore.compactRetaining(pendingDatabaseTransactionIds());
    }

    int markerCountForTesting() {
        if (databaseDirectory == null) {
            return 0;
        }
        Path directory = DelosDatabaseCommitDecision.directory(databaseDirectory);
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (DirectoryStream<Path> markers = Files.newDirectoryStream(directory, "commit-*.decision")) {
            int count = 0;
            for (Path ignored : markers) {
                count++;
            }
            return count;
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not count database decision markers", failure);
        }
    }

    private Set<MvccTransactionId> pendingDatabaseTransactionIds() {
        Path inheritedStore = PageVolumeMvccPaths.inheritedStoreDirectory(databaseDirectory);
        if (inheritedStore == null || !Files.isDirectory(inheritedStore)) {
            return Set.of();
        }

        Set<MvccTransactionId> pending = new LinkedHashSet<>();
        try (DirectoryStream<Path> mutationLogs =
                     Files.newDirectoryStream(inheritedStore, "*.pages" + PAGE_MUTATION_SUFFIX)) {
            for (Path mutationLogPath : mutationLogs) {
                String fileName = mutationLogPath.getFileName().toString();
                Path pageFile = mutationLogPath.resolveSibling(
                        fileName.substring(0, fileName.length() - PAGE_MUTATION_SUFFIX.length()));
                Path outcomeLogPath = PageVolumeMvccPaths.transactionOutcomeLogFileFor(pageFile);
                pending.addAll(MvccPageMutationLog.open(mutationLogPath)
                        .pendingDatabaseTransactionIds(
                                MvccTransactionOutcomeLog.open(outcomeLogPath)));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Could not scan pending database transaction decisions in " + inheritedStore,
                    failure);
        }
        return Set.copyOf(pending);
    }

    private void retireMarker(DelosDatabaseCommitDecision decision) {
        if (databaseDirectory == null) {
            return;
        }
        Path marker = DelosDatabaseCommitDecision.markerFile(
                databaseDirectory,
                decision.transactionId(),
                decision.commitSequence());
        try {
            if (Files.deleteIfExists(marker)) {
                MvccDurableFiles.forceParentDirectoryIfSupported(marker);
            }
            Path directory = marker.getParent();
            if (directory != null) {
                try {
                    if (Files.deleteIfExists(directory)) {
                        MvccDurableFiles.forceParentDirectoryIfSupported(directory);
                    }
                } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                    // Other unresolved decisions still require the directory.
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Could not retire database decision marker " + marker,
                    failure);
        }
    }
}
