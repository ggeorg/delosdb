package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import io.github.ggeorg.delosdb.storage.mvcc.store.MvccSubsystemRecoveryRecordStore;
import io.github.ggeorg.delosdb.storage.mvcc.store.MvccSubsystemRecoveryRecordStore.Subsystem;

/**
 * Strict MVCC recovery replay coordinator.
 *
 * <p>The lower-level {@link MvccPageRecoveryRunner} knows how to replay durable
 * page mutations through the transaction-outcome log. This coordinator adds the
 * Phase L replay contract around that primitive: optional subsystem recovery
 * metadata is validated before replay, replay remains idempotent, and callers
 * can require cross-subsystem completeness when a crash test intentionally
 * models row/index/overflow/free-space redo as one logical recovery unit.</p>
 */
public final class MvccRecoveryReplayEngine {
    private final MvccPageMutationLog mutationLog;
    private final MvccTransactionOutcomeLog outcomeLog;
    private final PageBackedMvccTableStore store;
    private final MvccSubsystemRecoveryRecordStore.ReplayPlan subsystemReplayPlan;
    private final Set<Subsystem> requiredSubsystems;

    public MvccRecoveryReplayEngine(
            MvccPageMutationLog mutationLog,
            MvccTransactionOutcomeLog outcomeLog,
            PageBackedMvccTableStore store) {
        this(mutationLog, outcomeLog, store, null, Set.of());
    }

    public MvccRecoveryReplayEngine(
            MvccPageMutationLog mutationLog,
            MvccTransactionOutcomeLog outcomeLog,
            PageBackedMvccTableStore store,
            MvccSubsystemRecoveryRecordStore.ReplayPlan subsystemReplayPlan,
            Set<Subsystem> requiredSubsystems) {
        this.mutationLog = Objects.requireNonNull(mutationLog, "mutationLog");
        this.outcomeLog = Objects.requireNonNull(outcomeLog, "outcomeLog");
        this.store = Objects.requireNonNull(store, "store");
        this.subsystemReplayPlan = subsystemReplayPlan == null
                ? MvccSubsystemRecoveryRecordStore.ReplayPlan.empty(null)
                : subsystemReplayPlan;
        this.requiredSubsystems = immutableSubsystemSet(requiredSubsystems);
    }

    public static ReplayResult recoverStrict(Path mutationLogPath, Path outcomeLogPath, Path tablePath)
            throws IOException {
        Objects.requireNonNull(mutationLogPath, "mutationLogPath");
        Objects.requireNonNull(outcomeLogPath, "outcomeLogPath");
        Objects.requireNonNull(tablePath, "tablePath");
        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tablePath)) {
            return new MvccRecoveryReplayEngine(
                    MvccPageMutationLog.open(mutationLogPath),
                    MvccTransactionOutcomeLog.open(outcomeLogPath),
                    store).recoverStrict();
        }
    }

    public static ReplayResult recoverStrict(
            Path mutationLogPath,
            Path outcomeLogPath,
            Path tablePath,
            MvccSubsystemRecoveryRecordStore recoveryRecordStore,
            Set<Subsystem> requiredSubsystems) throws IOException {
        Objects.requireNonNull(recoveryRecordStore, "recoveryRecordStore");
        Objects.requireNonNull(requiredSubsystems, "requiredSubsystems");
        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tablePath)) {
            return new MvccRecoveryReplayEngine(
                    MvccPageMutationLog.open(mutationLogPath),
                    MvccTransactionOutcomeLog.open(outcomeLogPath),
                    store,
                    recoveryRecordStore.replayPlan(),
                    requiredSubsystems).recoverStrict();
        }
    }

    /**
     * Replays mutations through the strict transaction-outcome authority.
     *
     * <p>Subsystem metadata validation happens before any page mutation replay.
     * That ordering is intentional: a crash simulation that contains row-page
     * redo without matching index/overflow/free-space/outcome metadata should
     * fail as an incomplete replay plan, not partially apply page records and
     * only then report a consistency problem.</p>
     */
    public ReplayResult recoverStrict() throws IOException {
        subsystemReplayPlan.requireCrossSubsystemCompleteness(requiredSubsystems);
        MvccPageRecoveryRunner.RecoveryResult pageResult =
                new MvccPageRecoveryRunner(mutationLog, store).recoverStrict(outcomeLog);
        return new ReplayResult(
                pageResult.appliedRecords(),
                pageResult.skippedExistingRecords(),
                subsystemReplayPlan.records().size(),
                requiredSubsystems.size());
    }

    public static Set<Subsystem> rowIndexOverflowFreeSpaceOutcomeSubsystems() {
        return EnumSet.of(
                Subsystem.ROW_PAGE,
                Subsystem.INDEX_PAGE,
                Subsystem.OVERFLOW_PAGE,
                Subsystem.FREE_SPACE_MAP,
                Subsystem.TRANSACTION_OUTCOME);
    }

    private static Set<Subsystem> immutableSubsystemSet(Set<Subsystem> requiredSubsystems) {
        if (requiredSubsystems == null || requiredSubsystems.isEmpty()) {
            return Set.of();
        }
        EnumSet<Subsystem> copy = EnumSet.noneOf(Subsystem.class);
        for (Subsystem subsystem : requiredSubsystems) {
            copy.add(Objects.requireNonNull(subsystem, "requiredSubsystems entry"));
        }
        return Set.copyOf(copy);
    }

    public record ReplayResult(
            int appliedRecords,
            int skippedExistingRecords,
            int subsystemRecords,
            int requiredSubsystems) {
        public ReplayResult {
            if (appliedRecords < 0 || skippedExistingRecords < 0
                    || subsystemRecords < 0 || requiredSubsystems < 0) {
                throw new IllegalArgumentException("recovery replay counts must not be negative");
            }
        }
    }
}
