package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionManager;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator;
import org.apache.derby.iapi.store.types.DelosVacuumOutcome;
import org.apache.derby.iapi.store.types.StoreDataValue;

/**
 * Owns purge policy, asynchronous maintenance registration, vacuum execution,
 * and maintenance diagnostics for one inherited MVCC table.
 */
final class MvccInheritedMaintenanceLifecycle implements AutoCloseable {
    private final PageVolumeMvccStateStore<StoreDataValue[]> pageVolumeStateStore;
    private final MvccTransactionManager transactions;
    private final MvccPurgeDaemon purgeDaemon = new MvccPurgeDaemon();
    private final MvccDatabaseMaintenanceService maintenanceService;
    private final MvccDatabaseMaintenanceService.Registration maintenanceRegistration;
    private final boolean ownsMaintenanceService;
    private final AtomicBoolean closeStarted;
    private final AtomicBoolean closed;
    private final AtomicReference<MvccInheritedTable.RecoveryRequired> recoveryRequired;
    private final MvccInheritedTableAccess tableAccess;

    private DelosVacuumOutcome lastVacuumOutcome = DelosVacuumOutcome.disabled();
    private long postCommitMaintenanceFailureCount;
    private String lastPostCommitMaintenanceFailure = "";
    private volatile Runnable postCommitMaintenanceHook = () -> { };

    MvccInheritedMaintenanceLifecycle(
            long segmentId,
            long containerId,
            PageVolumeMvccStateStore<StoreDataValue[]> pageVolumeStateStore,
            MvccTransactionManager transactions,
            MvccDatabaseMaintenanceService maintenanceService,
            boolean ownsMaintenanceService,
            AtomicBoolean closeStarted,
            AtomicBoolean closed,
            AtomicReference<MvccInheritedTable.RecoveryRequired> recoveryRequired,
            MvccInheritedTableAccess tableAccess) {
        this.pageVolumeStateStore = Objects.requireNonNull(pageVolumeStateStore, "pageVolumeStateStore");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.maintenanceService = Objects.requireNonNull(maintenanceService, "maintenanceService");
        this.ownsMaintenanceService = ownsMaintenanceService;
        this.closeStarted = Objects.requireNonNull(closeStarted, "closeStarted");
        this.closed = Objects.requireNonNull(closed, "closed");
        this.recoveryRequired = Objects.requireNonNull(recoveryRequired, "recoveryRequired");
        this.tableAccess = Objects.requireNonNull(tableAccess, "tableAccess");
        this.maintenanceRegistration = maintenanceService.register(new MaintenanceTarget(segmentId, containerId));
    }

    void afterCommit(int changedRows) {
        try {
            postCommitMaintenanceHook.run();
            runPurgeDaemonAfterCommit(changedRows);
        } catch (RuntimeException maintenanceFailure) {
            postCommitMaintenanceFailureCount++;
            lastPostCommitMaintenanceFailure = MvccInheritedTable.failureSummary(maintenanceFailure);
        }
    }

    DelosVacuumOutcome vacuumSafely() {
        return tableAccess.durable(DelosStorageBackupCoordinator.Mutation.VACUUM, () -> {
            lastVacuumOutcome = vacuumOutcome(pageVolumeStateStore.vacuumSafely(hasRetainedSnapshot()));
            return lastVacuumOutcome;
        });
    }

    DelosVacuumOutcome lastVacuumOutcome() {
        return tableAccess.read(() -> lastVacuumOutcome);
    }

    boolean hasRetainedSnapshot() {
        return transactions.activeTransactionCount() > 0 || transactions.retainedSnapshotCount() > 0;
    }

    void setPostCommitMaintenanceHook(Runnable hook) {
        postCommitMaintenanceHook = Objects.requireNonNull(hook, "hook");
    }

    long postCommitMaintenanceFailureCount() {
        return postCommitMaintenanceFailureCount;
    }

    String lastPostCommitMaintenanceFailure() {
        return lastPostCommitMaintenanceFailure;
    }

    long purgeDaemonScheduleCount() {
        return tableAccess.read(purgeDaemon::scheduleCount);
    }

    long purgeDaemonRunCount() {
        return tableAccess.read(purgeDaemon::runCount);
    }

    long purgeDaemonSkipCount() {
        return tableAccess.read(purgeDaemon::skipCount);
    }

    long purgeDaemonLastTriggerChangedRows() {
        return tableAccess.read(purgeDaemon::lastTriggerChangedRows);
    }

    String purgeDaemonLastDecision() {
        return tableAccess.read(purgeDaemon::lastDecision);
    }

    long purgeDaemonLastVisibilityDebtScore() {
        return tableAccess.read(purgeDaemon::lastVisibilityDebtScore);
    }

    String purgeDaemonLastVisibilityDebtSummary() {
        return tableAccess.read(purgeDaemon::lastVisibilityDebtSummary);
    }

    MvccDatabaseMaintenanceService maintenanceService() {
        return maintenanceService;
    }

    private void runPurgeDaemonAfterCommit(int changedRows) {
        MvccVisibilityDebtPolicy.Snapshot debt = visibilityDebtSnapshot();
        if (purgeDaemon.asynchronousEnabled()) {
            if (!purgeDaemon.eligibleAfterCommit(changedRows, debt)) {
                return;
            }
            purgeDaemon.recordAsyncScheduled(changedRows, debt);
            maintenanceRegistration.request(
                    MvccDatabaseMaintenanceService.Priority.from(debt),
                    MvccDatabaseMaintenanceService.Trigger.COMMIT);
            return;
        }
        purgeDaemon.maybeRunAfterCommit(
                changedRows,
                this::visibilityDebtSnapshot,
                this::hasRetainedSnapshot,
                () -> vacuumOutcome(pageVolumeStateStore.vacuumSafely(false)))
                .ifPresent(outcome -> lastVacuumOutcome = outcome);
    }

    private Optional<MvccDatabaseMaintenanceService.Priority> periodicMaintenancePriority() {
        if (closeStarted.get() || closed.get() || recoveryRequired.get() != null) {
            return Optional.empty();
        }
        return tableAccess.read(() -> {
            if (closed.get()) {
                return Optional.empty();
            }
            MvccVisibilityDebtPolicy.Snapshot debt = visibilityDebtSnapshot();
            if (!purgeDaemon.periodicMaintenanceEligible(debt)) {
                return Optional.empty();
            }
            purgeDaemon.recordPeriodicScheduled(debt);
            return Optional.of(MvccDatabaseMaintenanceService.Priority.from(debt));
        });
    }

    private void runScheduledMaintenance(MvccDatabaseMaintenanceService.Trigger trigger) {
        if (closeStarted.get() || closed.get() || recoveryRequired.get() != null) {
            return;
        }
        tableAccess.durable(DelosStorageBackupCoordinator.Mutation.ASYNCHRONOUS_MAINTENANCE, () -> {
            if (closed.get()) {
                return;
            }
            if (hasRetainedSnapshot()) {
                purgeDaemon.recordAsyncSkip("retained inherited MVCC transaction or scan");
                return;
            }
            if (!purgeDaemon.eligibleVisibilityDebt(visibilityDebtSnapshot())) {
                return;
            }
            DelosVacuumOutcome outcome = vacuumOutcome(pageVolumeStateStore.vacuumSafely(false));
            lastVacuumOutcome = outcome;
            purgeDaemon.recordAsyncRun(outcome);
        });
    }

    private MvccVisibilityDebtPolicy.Snapshot visibilityDebtSnapshot() {
        long obsoleteVersions = Math.max(
                0L,
                (long) pageVolumeStateStore.physicalVersionCount() - pageVolumeStateStore.logicalRowCount());
        return new MvccVisibilityDebtPolicy.Snapshot(
                pageVolumeStateStore.visibilityMapPrunablePageCount(),
                pageVolumeStateStore.visibilityMapOldVersionPageCount(),
                pageVolumeStateStore.visibilityMapTombstonePageCount(),
                pageVolumeStateStore.purgeQueuePendingCount(),
                obsoleteVersions);
    }

    @Override
    public void close() {
        if (ownsMaintenanceService) {
            maintenanceService.close();
        }
        maintenanceRegistration.close();
    }

    private static DelosVacuumOutcome vacuumOutcome(PageVolumeMvccStateStore.VacuumOutcome outcome) {
        return new DelosVacuumOutcome(
                outcome.skipped(),
                outcome.reason(),
                outcome.removedVersions(),
                outcome.remainingVersions());
    }

    private final class MaintenanceTarget implements MvccDatabaseMaintenanceService.Target {
        private final String identity;

        private MaintenanceTarget(long segmentId, long containerId) {
            identity = segmentId + ":" + containerId;
        }

        @Override
        public String maintenanceIdentity() {
            return identity;
        }

        @Override
        public Optional<MvccDatabaseMaintenanceService.Priority> periodicMaintenancePriority() {
            return MvccInheritedMaintenanceLifecycle.this.periodicMaintenancePriority();
        }

        @Override
        public void runMaintenance(MvccDatabaseMaintenanceService.Trigger trigger) {
            MvccInheritedMaintenanceLifecycle.this.runScheduledMaintenance(trigger);
        }
    }
}
