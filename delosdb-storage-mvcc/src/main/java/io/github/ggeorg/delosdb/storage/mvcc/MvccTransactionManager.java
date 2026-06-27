package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Small transaction table for the experimental MVCC kernel.
 *
 * <p>This class intentionally models only the MVCC vocabulary needed before any
 * Derby store integration: transaction id allocation, commit sequence
 * assignment, active-transaction snapshots, and oldest active snapshot
 * discovery. It is synchronized to keep the prototype deterministic and safe
 * for focused tests.</p>
 */
public final class MvccTransactionManager implements MvccTransactionCatalog {
    private long nextTransactionId = 1L;
    private long currentCommitSequence = 0L;
    private long nextSnapshotLeaseId = 1L;
    private MvccTransactionId compactedTransactionIdThrough = MvccTransactionId.NONE;
    private MvccCommitSequence compactedCommittedVisibleThrough = MvccCommitSequence.NONE;
    private final Map<MvccTransactionId, TransactionState> activeTransactions = new HashMap<>();
    private final Map<MvccTransactionId, TransactionOutcome> retainedOutcomes = new HashMap<>();
    private final Set<MvccTransactionId> compactedAbortedTransactions = new LinkedHashSet<>();
    private final Map<Long, MvccCommitSequence> retainedSnapshotWatermarks = new LinkedHashMap<>();
    private static final BooleanSupplier NEVER_SUPPRESS_LOGGING = () -> false;

    private final MvccTransactionStatusStore statusStore;
    private final MvccLogWriter logWriter;
    private final BooleanSupplier loggingSuppressed;

    public MvccTransactionManager() {
        this(MvccTransactionStatusStore.disabled());
    }

    public MvccTransactionManager(MvccTransactionStatusStore statusStore) {
        this(statusStore, MvccLogWriter.disabled());
    }

    public MvccTransactionManager(MvccTransactionStatusStore statusStore, MvccLogWriter logWriter) {
        this(statusStore, logWriter, NEVER_SUPPRESS_LOGGING);
    }

    public MvccTransactionManager(
            MvccTransactionStatusStore statusStore,
            MvccLogWriter logWriter,
            BooleanSupplier loggingSuppressed) {
        this.statusStore = Objects.requireNonNull(statusStore, "statusStore");
        this.logWriter = Objects.requireNonNull(logWriter, "logWriter");
        this.loggingSuppressed = Objects.requireNonNull(loggingSuppressed, "loggingSuppressed");
        recoverDurableStatuses();
        compactRetainedOutcomes();
    }

    public synchronized MvccTransaction begin() {
        MvccTransactionId id = new MvccTransactionId(nextTransactionId++);
        appendBeginIfEnabled(id);
        statusStore.recordActive(id);
        activeTransactions.put(id, new TransactionState(
                new MvccCommitSequence(currentCommitSequence),
                1L));
        return new MvccTransaction(id);
    }

    public synchronized MvccSnapshot snapshot(MvccTransaction transaction) {
        return snapshot(transaction, MvccCommandSequence.LATEST_VISIBLE);
    }

    public synchronized MvccSnapshot snapshot(
            MvccTransaction transaction,
            MvccCommandSequence visibleThroughCommand) {
        requireActive(transaction);
        return captureSnapshot(transaction, visibleThroughCommand);
    }

    /**
     * Captures the next statement snapshot for the transaction. The returned
     * command sequence is the write stamp for that statement; the snapshot uses
     * the same boundary so it sees earlier own commands but not versions written
     * by the current statement.
     */
    public synchronized MvccStatementSnapshot beginStatement(MvccTransaction transaction) {
        TransactionState state = requireActive(transaction);
        MvccCommandSequence commandSequence = MvccCommandSequence.of(state.nextCommandSequence++);
        return new MvccStatementSnapshot(
                transaction,
                commandSequence,
                captureSnapshot(transaction, commandSequence));
    }

    /**
     * Opens a retained snapshot. Cleanup/vacuum must keep history required by
     * the returned view until the lease is closed, even if the owning
     * transaction finishes first. This is the explicit snapshot watermark used
     * by A45 to prevent unsafe pruning instead of merely detecting it later.
     */
    public synchronized MvccSnapshotLease openSnapshot(MvccTransaction transaction) {
        return openSnapshot(transaction, MvccCommandSequence.LATEST_VISIBLE);
    }

    public synchronized MvccSnapshotLease openSnapshot(
            MvccTransaction transaction,
            MvccCommandSequence visibleThroughCommand) {
        requireActive(transaction);
        MvccSnapshot snapshot = captureSnapshot(transaction, visibleThroughCommand);
        long leaseId = nextSnapshotLeaseId++;
        retainedSnapshotWatermarks.put(leaseId, snapshot.visibleThrough());
        return new MvccSnapshotLease(snapshot, () -> closeSnapshotLease(leaseId));
    }

    public synchronized MvccCommitSequence commit(MvccTransaction transaction) {
        requireActive(transaction);
        MvccCommitSequence sequence = new MvccCommitSequence(currentCommitSequence + 1L);
        appendCommitIfEnabled(transaction.id(), sequence);
        statusStore.recordCommitted(transaction.id(), sequence);
        currentCommitSequence = sequence.value();
        activeTransactions.remove(transaction.id());
        retainedOutcomes.put(transaction.id(), TransactionOutcome.committed(sequence));
        compactRetainedOutcomes();
        return sequence;
    }

    public synchronized void abort(MvccTransaction transaction) {
        requireActive(transaction);
        appendAbortIfEnabled(transaction.id());
        statusStore.recordAborted(transaction.id());
        activeTransactions.remove(transaction.id());
        retainedOutcomes.put(transaction.id(), TransactionOutcome.aborted());
        compactRetainedOutcomes();
    }

    public synchronized MvccCommitSequence newestCommitSequence() {
        return new MvccCommitSequence(currentCommitSequence);
    }

    public synchronized int activeTransactionCount() {
        return activeTransactions.size();
    }

    /**
     * Returns the number of terminal transaction outcomes still retained with
     * exact per-transaction state. This is primarily a proof hook for the MVCC
     * transaction-table compaction boundary; compacted terminal outcomes remain
     * visible through the catalog methods without keeping a full
     * {@link TransactionState} per historical transaction.
     */
    public synchronized int retainedTransactionOutcomeCount() {
        return retainedOutcomes.size();
    }

    /**
     * Returns the transaction-id prefix whose terminal outcomes were compacted.
     */
    public synchronized MvccTransactionId compactedTransactionIdThrough() {
        return compactedTransactionIdThrough;
    }

    /**
     * Returns the oldest snapshot high-water mark still held by an active
     * transaction. If no transaction is active, cleanup may use the current
     * commit sequence as the safe high-water mark.
     */
    public synchronized MvccCommitSequence oldestActiveVisibleThrough() {
        MvccCommitSequence oldest = oldestActiveTransactionSnapshotSequence();
        return oldest == null ? newestCommitSequence() : oldest;
    }

    /**
     * Returns the oldest MVCC visibility high-water mark retained either by an
     * active transaction or by an explicitly opened snapshot lease. Cleanup and
     * vacuum must use this boundary so a long-lived snapshot cannot lose the
     * history it still needs.
     */
    public synchronized MvccCommitSequence oldestRetainedVisibleThrough() {
        MvccCommitSequence oldest = oldestActiveTransactionSnapshotSequence();
        for (MvccCommitSequence retained : retainedSnapshotWatermarks.values()) {
            if (oldest == null || retained.compareTo(oldest) < 0) {
                oldest = retained;
            }
        }
        return oldest == null ? newestCommitSequence() : oldest;
    }

    public synchronized int retainedSnapshotCount() {
        return retainedSnapshotWatermarks.size();
    }

    /**
     * Captures immutable transaction outcome metadata for visibility checks.
     * The resulting catalog can be reused for row-version decisions without
     * re-consulting this live transaction manager.
     */
    public synchronized MvccCapturedVisibility captureVisibility(MvccSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        Map<MvccTransactionId, MvccCapturedVisibility.CapturedTransaction> captured = new LinkedHashMap<>();
        for (Map.Entry<MvccTransactionId, TransactionState> entry : activeTransactions.entrySet()) {
            captured.put(entry.getKey(), new MvccCapturedVisibility.CapturedTransaction(
                    MvccTransactionStatus.ACTIVE,
                    MvccCommitSequence.NONE));
        }
        for (Map.Entry<MvccTransactionId, TransactionOutcome> entry : retainedOutcomes.entrySet()) {
            captured.put(entry.getKey(), new MvccCapturedVisibility.CapturedTransaction(
                    entry.getValue().status,
                    entry.getValue().commitSequence));
        }
        return new MvccCapturedVisibility(
                snapshot,
                captured,
                compactedTransactionIdThrough,
                compactedCommittedVisibleThrough,
                compactedAbortedTransactions);
    }

    @Override
    public synchronized MvccTransactionStatus statusOf(MvccTransactionId transactionId) {
        if (transactionId == null || transactionId.isNone()) {
            return MvccTransactionStatus.ABORTED;
        }
        if (activeTransactions.containsKey(transactionId)) {
            return MvccTransactionStatus.ACTIVE;
        }
        TransactionOutcome outcome = retainedOutcomes.get(transactionId);
        if (outcome != null) {
            return outcome.status;
        }
        if (transactionId.compareTo(compactedTransactionIdThrough) <= 0) {
            return compactedAbortedTransactions.contains(transactionId)
                    ? MvccTransactionStatus.ABORTED
                    : MvccTransactionStatus.COMMITTED;
        }
        return MvccTransactionStatus.RECOVERY_PENDING;
    }

    @Override
    public synchronized Optional<MvccCommitSequence> commitSequenceOf(MvccTransactionId transactionId) {
        if (transactionId == null || transactionId.isNone()) {
            return Optional.empty();
        }
        TransactionOutcome outcome = retainedOutcomes.get(transactionId);
        if (outcome != null && outcome.status == MvccTransactionStatus.COMMITTED) {
            return Optional.of(outcome.commitSequence);
        }
        if (transactionId.compareTo(compactedTransactionIdThrough) <= 0
                && !compactedAbortedTransactions.contains(transactionId)) {
            return Optional.of(compactedCommittedVisibleThrough);
        }
        return Optional.empty();
    }

    private void recoverDurableStatuses() {
        Map<MvccTransactionId, MvccTransactionStatusRecord> recovered = statusStore.recoverStatuses();
        long maxTransactionId = 0L;
        long maxCommitSequence = 0L;
        for (MvccTransactionStatusRecord record : recovered.values()) {
            maxTransactionId = Math.max(maxTransactionId, record.transactionId().value());
            if (record.status() == MvccTransactionStatus.COMMITTED) {
                maxCommitSequence = Math.max(maxCommitSequence, record.commitSequence().value());
            }
            retainedOutcomes.put(record.transactionId(), new TransactionOutcome(
                    record.status(),
                    record.commitSequence()));
        }
        nextTransactionId = Math.max(nextTransactionId, maxTransactionId + 1L);
        currentCommitSequence = Math.max(currentCommitSequence, maxCommitSequence);
    }

    private void appendBeginIfEnabled(MvccTransactionId transactionId) {
        if (!loggingSuppressed.getAsBoolean()) {
            logWriter.appendBegin(transactionId);
        }
    }

    private void appendCommitIfEnabled(MvccTransactionId transactionId, MvccCommitSequence sequence) {
        if (!loggingSuppressed.getAsBoolean()) {
            logWriter.appendCommit(transactionId, sequence);
        }
    }

    private void appendAbortIfEnabled(MvccTransactionId transactionId) {
        if (!loggingSuppressed.getAsBoolean()) {
            logWriter.appendAbort(transactionId);
        }
    }

    private MvccSnapshot captureSnapshot(
            MvccTransaction transaction,
            MvccCommandSequence visibleThroughCommand) {
        Set<MvccTransactionId> active = new LinkedHashSet<>(activeTransactions.keySet());
        active.remove(transaction.id());
        return new MvccSnapshot(
                transaction.id(),
                new MvccCommitSequence(currentCommitSequence),
                active,
                visibleThroughCommand);
    }

    private MvccCommitSequence oldestActiveTransactionSnapshotSequence() {
        MvccCommitSequence oldest = null;
        for (TransactionState state : activeTransactions.values()) {
            if (oldest == null || state.snapshotSequence.compareTo(oldest) < 0) {
                oldest = state.snapshotSequence;
            }
        }
        return oldest;
    }

    private synchronized void closeSnapshotLease(long leaseId) {
        retainedSnapshotWatermarks.remove(leaseId);
        compactRetainedOutcomes();
    }

    private void compactRetainedOutcomes() {
        MvccCommitSequence safeVisibleThrough = oldestRetainedVisibleThrough();
        long nextCompactable = compactedTransactionIdThrough.value() + 1L;
        MvccCommitSequence compactedCommittedMax = compactedCommittedVisibleThrough;
        while (true) {
            MvccTransactionId transactionId = new MvccTransactionId(nextCompactable);
            if (activeTransactions.containsKey(transactionId)) {
                break;
            }
            TransactionOutcome outcome = retainedOutcomes.get(transactionId);
            if (outcome == null) {
                break;
            }
            if (outcome.status == MvccTransactionStatus.COMMITTED
                    && outcome.commitSequence.isAfter(safeVisibleThrough)) {
                break;
            }
            if (outcome.status == MvccTransactionStatus.ABORTED
                    || outcome.status == MvccTransactionStatus.RECOVERY_PENDING) {
                compactedAbortedTransactions.add(transactionId);
            } else if (outcome.status == MvccTransactionStatus.COMMITTED
                    && outcome.commitSequence.compareTo(compactedCommittedMax) > 0) {
                compactedCommittedMax = outcome.commitSequence;
            }
            retainedOutcomes.remove(transactionId);
            compactedTransactionIdThrough = transactionId;
            nextCompactable++;
        }
        compactedCommittedVisibleThrough = compactedCommittedMax;
    }

    private TransactionState requireActive(MvccTransaction transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("transaction must not be null");
        }
        TransactionState state = activeTransactions.get(transaction.id());
        if (state == null) {
            throw new IllegalStateException("transaction is not active: " + transaction.id()
                    + " status=" + statusOf(transaction.id()));
        }
        return state;
    }

    private static final class TransactionState {
        private final MvccCommitSequence snapshotSequence;
        private long nextCommandSequence;

        private TransactionState(
                MvccCommitSequence snapshotSequence,
                long nextCommandSequence) {
            this.snapshotSequence = snapshotSequence;
            this.nextCommandSequence = nextCommandSequence;
        }
    }

    private static final class TransactionOutcome {
        private final MvccTransactionStatus status;
        private final MvccCommitSequence commitSequence;

        private TransactionOutcome(MvccTransactionStatus status, MvccCommitSequence commitSequence) {
            this.status = Objects.requireNonNull(status, "status");
            this.commitSequence = Objects.requireNonNull(commitSequence, "commitSequence");
        }

        private static TransactionOutcome committed(MvccCommitSequence commitSequence) {
            return new TransactionOutcome(MvccTransactionStatus.COMMITTED, commitSequence);
        }

        private static TransactionOutcome aborted() {
            return new TransactionOutcome(MvccTransactionStatus.ABORTED, MvccCommitSequence.NONE);
        }
    }
}
