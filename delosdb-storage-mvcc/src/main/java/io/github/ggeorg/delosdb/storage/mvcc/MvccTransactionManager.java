package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Transaction table and commit-sequence authority for the MVCC engine.
 *
 * <p>This class intentionally models only the MVCC vocabulary needed before any
 * Derby store integration: transaction id allocation, commit sequence
 * assignment, active-transaction snapshots, and oldest active snapshot
 * discovery. Its synchronization protects transaction state and commit-sequence publication
 * for focused tests.</p>
 */
public final class MvccTransactionManager implements MvccTransactionCatalog {
    private long nextTransactionId = 1L;
    private long nextReadOnlyTransactionId = Long.MAX_VALUE;
    private long currentCommitSequence = 0L;
    private long nextSnapshotLeaseId = 1L;
    private MvccTransactionId compactedTransactionIdThrough = MvccTransactionId.NONE;
    private MvccCommitSequence compactedCommittedVisibleThrough = MvccCommitSequence.NONE;
    private final Map<MvccTransactionId, TransactionState> activeTransactions = new HashMap<>();
    private final Map<MvccTransactionId, TransactionOutcome> retainedOutcomes = new HashMap<>();
    private final Set<MvccTransactionId> compactedAbortedTransactions = new LinkedHashSet<>();
    private final Map<MvccTransactionId, MvccCommitSequence> compactedCommittedSequences = new LinkedHashMap<>();
    private final Map<Long, MvccCommitSequence> retainedSnapshotWatermarks = new LinkedHashMap<>();
    private final MvccTransactionStatusStore statusStore;
    public MvccTransactionManager() {
        this(MvccTransactionStatusStore.disabled());
    }

    public MvccTransactionManager(MvccTransactionStatusStore statusStore) {
        this.statusStore = Objects.requireNonNull(statusStore, "statusStore");
        recoverDurableStatuses();
        compactRetainedOutcomes();
    }

    public synchronized MvccTransaction begin() {
        MvccTransactionId id = new MvccTransactionId(nextTransactionId++);
        statusStore.recordActive(id);
        activeTransactions.put(id, new TransactionState(
                new MvccCommitSequence(currentCommitSequence),
                1L,
                true));
        return new MvccTransaction(id);
    }

    /**
     * Begin an in-memory read-only transaction. It participates in active
     * snapshot and vacuum-watermark accounting but never writes transaction
     * status records because it cannot own durable row versions.
     */
    public synchronized MvccTransaction beginReadOnly() {
        if (nextReadOnlyTransactionId <= nextTransactionId) {
            throw new IllegalStateException("MVCC read-only transaction id space exhausted");
        }
        MvccTransactionId id = new MvccTransactionId(nextReadOnlyTransactionId--);
        activeTransactions.put(id, new TransactionState(
                new MvccCommitSequence(currentCommitSequence),
                1L,
                false));
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
        return commitBatch(List.of(transaction)).get(0);
    }

    /**
     * Assigns ordered commit sequences and publishes all terminal transaction
     * statuses through one forced status-store append.
     */
    public synchronized List<MvccCommitSequence> commitBatch(List<MvccTransaction> transactions) {
        PreparedCommitBatch prepared = prepareCommitBatch(transactions);
        publishPreparedCommitBatch(prepared);
        return prepared.sequences();
    }

    /**
     * Reserves ordered commit sequences without publishing terminal status.
     *
     * <p>The caller may durably stage transaction payloads using these
     * sequences before the shared COMMITTED append.  No transaction becomes
     * visible and {@link #newestCommitSequence()} does not advance until
     * {@link #publishPreparedCommitBatch(PreparedCommitBatch)} succeeds.</p>
     */
    public synchronized PreparedCommitBatch prepareCommitBatch(List<MvccTransaction> transactions) {
        transactions = List.copyOf(Objects.requireNonNull(transactions, "transactions"));
        if (transactions.isEmpty()) {
            return new PreparedCommitBatch(currentCommitSequence, List.of());
        }
        Set<MvccTransactionId> uniqueIds = new LinkedHashSet<>();
        List<PreparedCommit> commits = new ArrayList<>(transactions.size());
        long nextSequence = currentCommitSequence;
        for (MvccTransaction transaction : transactions) {
            transaction = Objects.requireNonNull(transaction, "transactions entry");
            TransactionState state = requireActive(transaction);
            if (!state.durableStatusTracked) {
                throw new IllegalStateException("read-only MVCC transaction cannot commit: " + transaction.id());
            }
            if (!uniqueIds.add(transaction.id())) {
                throw new IllegalArgumentException("duplicate MVCC transaction in commit batch: "
                        + transaction.id());
            }
            commits.add(new PreparedCommit(transaction, new MvccCommitSequence(++nextSequence)));
        }
        return new PreparedCommitBatch(currentCommitSequence, commits);
    }

    /** Publishes a previously prepared batch through one forced status append. */
    public synchronized void publishPreparedCommitBatch(PreparedCommitBatch preparedBatch) {
        Objects.requireNonNull(preparedBatch, "preparedBatch");
        if (preparedBatch.commits().isEmpty()) {
            return;
        }
        if (preparedBatch.baseCommitSequence() != currentCommitSequence) {
            throw new IllegalStateException("MVCC prepared commit batch is stale: expected base "
                    + currentCommitSequence + " but was " + preparedBatch.baseCommitSequence());
        }

        List<MvccTransactionStatusStore.CommittedStatus> durableStatuses =
                new ArrayList<>(preparedBatch.commits().size());
        long previousSequence = currentCommitSequence;
        for (PreparedCommit prepared : preparedBatch.commits()) {
            TransactionState state = requireActive(prepared.transaction());
            if (!state.durableStatusTracked) {
                throw new IllegalStateException("read-only MVCC transaction cannot commit: "
                        + prepared.transaction().id());
            }
            if (prepared.commitSequence().value() <= previousSequence) {
                throw new IllegalArgumentException("prepared commit sequences must increase beyond "
                        + currentCommitSequence);
            }
            previousSequence = prepared.commitSequence().value();
            durableStatuses.add(new MvccTransactionStatusStore.CommittedStatus(
                    prepared.transaction().id(), prepared.commitSequence()));
        }

        statusStore.recordCommittedBatch(durableStatuses);
        currentCommitSequence = previousSequence;
        for (PreparedCommit prepared : preparedBatch.commits()) {
            activeTransactions.remove(prepared.transaction().id());
            retainedOutcomes.put(
                    prepared.transaction().id(),
                    TransactionOutcome.committed(prepared.commitSequence()));
        }
        compactRetainedOutcomes();
    }

    public record PreparedCommit(MvccTransaction transaction, MvccCommitSequence commitSequence) {
        public PreparedCommit {
            transaction = Objects.requireNonNull(transaction, "transaction");
            commitSequence = Objects.requireNonNull(commitSequence, "commitSequence");
            if (commitSequence.equals(MvccCommitSequence.NONE)) {
                throw new IllegalArgumentException("prepared commit sequence must be present");
            }
        }
    }

    public record PreparedCommitBatch(long baseCommitSequence, List<PreparedCommit> commits) {
        public PreparedCommitBatch {
            if (baseCommitSequence < 0L) {
                throw new IllegalArgumentException("base commit sequence must not be negative");
            }
            commits = List.copyOf(Objects.requireNonNull(commits, "commits"));
        }

        public List<MvccCommitSequence> sequences() {
            return commits.stream().map(PreparedCommit::commitSequence).toList();
        }
    }

    public synchronized void abort(MvccTransaction transaction) {
        TransactionState state = requireActive(transaction);
        activeTransactions.remove(transaction.id());
        if (!state.durableStatusTracked) {
            return;
        }
        statusStore.recordAborted(transaction.id());
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
            if (entry.getValue().durableStatusTracked) {
                captured.put(entry.getKey(), new MvccCapturedVisibility.CapturedTransaction(
                        MvccTransactionStatus.ACTIVE,
                        MvccCommitSequence.NONE));
            }
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
                compactedAbortedTransactions,
                compactedCommittedSequences);
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
            MvccCommitSequence compactedExact = compactedCommittedSequences.get(transactionId);
            return Optional.of(compactedExact == null ? compactedCommittedVisibleThrough : compactedExact);
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

    private MvccSnapshot captureSnapshot(
            MvccTransaction transaction,
            MvccCommandSequence visibleThroughCommand) {
        Set<MvccTransactionId> active = new LinkedHashSet<>();
        for (Map.Entry<MvccTransactionId, TransactionState> entry : activeTransactions.entrySet()) {
            if (entry.getValue().durableStatusTracked) {
                active.add(entry.getKey());
            }
        }
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
            } else if (outcome.status == MvccTransactionStatus.COMMITTED) {
                compactedCommittedSequences.put(transactionId, outcome.commitSequence);
                if (outcome.commitSequence.compareTo(compactedCommittedMax) > 0) {
                    compactedCommittedMax = outcome.commitSequence;
                }
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
        private final boolean durableStatusTracked;
        private long nextCommandSequence;

        private TransactionState(
                MvccCommitSequence snapshotSequence,
                long nextCommandSequence,
                boolean durableStatusTracked) {
            this.snapshotSequence = snapshotSequence;
            this.nextCommandSequence = nextCommandSequence;
            this.durableStatusTracked = durableStatusTracked;
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
