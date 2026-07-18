package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.derby.iapi.store.types.DelosDatabaseCommitDecision;
import org.apache.derby.iapi.store.types.DelosRawStoreCommitParticipant;
import org.apache.derby.iapi.store.types.DelosStorageCommitCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageCoordinatedCommitTable;
import org.apache.derby.iapi.store.types.DelosStorageSavepointParticipant;
import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageScan;
import org.apache.derby.iapi.store.types.DelosStorageTable;
import org.apache.derby.iapi.store.types.DelosStorageTableKey;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.shared.common.error.StandardException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccPaths;

/** Proofs the active Derby-facing MVCC storage transaction registry path. */
final class MvccInheritedTransactionLifecycleTest {
    @TempDir
    Path databaseDirectory;

    @AfterEach
    void clearRegistry() {
        DelosStorageTransactionRegistry.clearForTesting();
    }

    @Test
    void oneDerbyTransactionCommitsTwoMvccTablesAndReopensBoth() {
        Object derbyTransaction = new Object();
        MvccInheritedStore store = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable accounts = openTable(store, 1, 101);
        MvccInheritedTable ledger = openTable(store, 1, 102);

        DelosStorageTransaction accountsTx = accounts.beginTransaction();
        DelosStorageTransaction ledgerTx = ledger.beginTransaction();
        DelosStorageTransactionRegistry.register(derbyTransaction, accounts, accountsTx);
        DelosStorageTransactionRegistry.register(derbyTransaction, ledger, ledgerTx);

        accounts.insert(1L, durableEmptyRow(), accountsTx);
        ledger.insert(1L, durableEmptyRow(), ledgerTx);

        assertEquals(2, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        DelosStorageTransactionRegistry.commit(derbyTransaction);
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        store.close();

        MvccInheritedStore reopenedStore = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable reopenedAccounts = openTable(reopenedStore, 1, 101);
        MvccInheritedTable reopenedLedger = openTable(reopenedStore, 1, 102);

        assertTrue(read(reopenedAccounts, 1L).isPresent());
        assertTrue(read(reopenedLedger, 1L).isPresent());
        reopenedStore.close();
    }

    @Test
    void mixedRawStoreDecisionCommitsMultipleMvccParticipants() throws Exception {
        Object derbyTransaction = new Object();
        MvccInheritedStore store = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable accounts = openTable(store, 11, 1101);
        MvccInheritedTable ledger = openTable(store, 11, 1102);

        DelosStorageTransactionRegistry.registerWriteIntent(derbyTransaction, false, false);
        DelosStorageTransactionRegistry.registerWriteIntent(derbyTransaction, true, false);
        DelosStorageTransactionRegistry.registerWriteIntent(derbyTransaction, true, false);

        DelosStorageTransaction accountsTx = accounts.beginTransaction();
        DelosStorageTransaction ledgerTx = ledger.beginTransaction();
        DelosStorageTransactionRegistry.register(derbyTransaction, accounts, accountsTx);
        DelosStorageTransactionRegistry.register(derbyTransaction, ledger, ledgerTx);
        accounts.insert(1L, durableEmptyRow(), accountsTx);
        ledger.insert(1L, durableEmptyRow(), ledgerTx);

        CapturingRawStoreParticipant rawStore = new CapturingRawStoreParticipant();
        DelosStorageTransactionRegistry.CommitPreparation prepared =
                DelosStorageTransactionRegistry.prepareCommit(derbyTransaction, rawStore);
        assertTrue(prepared.requiresRawStoreDecision());
        writeRawStoreDecision(rawStore.decision);
        DelosStorageTransactionRegistry.completeCommit(prepared);
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        store.close();

        MvccInheritedStore reopenedStore = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable reopenedAccounts = openTable(reopenedStore, 11, 1101);
        MvccInheritedTable reopenedLedger = openTable(reopenedStore, 11, 1102);
        assertTrue(read(reopenedAccounts, 1L).isPresent());
        assertTrue(read(reopenedLedger, 1L).isPresent());
        reopenedStore.close();
    }

    @Test
    void mixedRawStoreRollbackAbortsEveryPreparedMvccParticipant() throws Exception {
        Object derbyTransaction = new Object();
        MvccInheritedStore store = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable accounts = openTable(store, 12, 1201);
        MvccInheritedTable ledger = openTable(store, 12, 1202);

        DelosStorageTransactionRegistry.registerWriteIntent(derbyTransaction, false, false);
        DelosStorageTransactionRegistry.registerWriteIntent(derbyTransaction, true, false);
        DelosStorageTransactionRegistry.registerWriteIntent(derbyTransaction, true, false);

        DelosStorageTransaction accountsTx = accounts.beginTransaction();
        DelosStorageTransaction ledgerTx = ledger.beginTransaction();
        DelosStorageTransactionRegistry.register(derbyTransaction, accounts, accountsTx);
        DelosStorageTransactionRegistry.register(derbyTransaction, ledger, ledgerTx);
        accounts.insert(1L, durableEmptyRow(), accountsTx);
        ledger.insert(1L, durableEmptyRow(), ledgerTx);

        CapturingRawStoreParticipant rawStore = new CapturingRawStoreParticipant();
        DelosStorageTransactionRegistry.CommitPreparation prepared =
                DelosStorageTransactionRegistry.prepareCommit(derbyTransaction, rawStore);
        DelosStorageTransactionRegistry.abortPreparedCommit(prepared);
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        store.close();

        MvccInheritedStore reopenedStore = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable reopenedAccounts = openTable(reopenedStore, 12, 1201);
        MvccInheritedTable reopenedLedger = openTable(reopenedStore, 12, 1202);
        assertTrue(read(reopenedAccounts, 1L).isEmpty());
        assertTrue(read(reopenedLedger, 1L).isEmpty());
        reopenedStore.close();
    }

    @Test
    void rawStoreDecisionRecoversMixedPublicationFailure() throws Exception {
        Object derbyTransaction = new Object();
        MvccInheritedStore store = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable accounts = openTable(store, 13, 1301);
        MvccInheritedTable ledger = openTable(store, 13, 1302);
        accounts.setPagePublicationHookForTesting((stage, changes) -> {
            if (stage == io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore
                    .PublicationStage.OUTCOME_FENCE) {
                throw new IllegalStateException("injected mixed publication failure");
            }
        });

        DelosStorageTransactionRegistry.registerWriteIntent(derbyTransaction, false, false);
        DelosStorageTransactionRegistry.registerWriteIntent(derbyTransaction, true, false);
        DelosStorageTransactionRegistry.registerWriteIntent(derbyTransaction, true, false);

        DelosStorageTransaction accountsTx = accounts.beginTransaction();
        DelosStorageTransaction ledgerTx = ledger.beginTransaction();
        DelosStorageTransactionRegistry.register(derbyTransaction, accounts, accountsTx);
        DelosStorageTransactionRegistry.register(derbyTransaction, ledger, ledgerTx);
        accounts.insert(1L, durableEmptyRow(), accountsTx);
        ledger.insert(1L, durableEmptyRow(), ledgerTx);

        CapturingRawStoreParticipant rawStore = new CapturingRawStoreParticipant();
        DelosStorageTransactionRegistry.CommitPreparation prepared =
                DelosStorageTransactionRegistry.prepareCommit(derbyTransaction, rawStore);
        writeRawStoreDecision(rawStore.decision);
        assertThrows(
                MvccDatabaseCommitCoordinator.DatabaseCommitRecoveryRequiredException.class,
                () -> DelosStorageTransactionRegistry.completeCommit(prepared));
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        store.close();

        MvccInheritedStore reopenedStore = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable reopenedAccounts = openTable(reopenedStore, 13, 1301);
        MvccInheritedTable reopenedLedger = openTable(reopenedStore, 13, 1302);
        assertTrue(read(reopenedAccounts, 1L).isPresent());
        assertTrue(read(reopenedLedger, 1L).isPresent());
        reopenedStore.close();
    }

    @Test
    void databaseDecisionRecoversEveryParticipantAfterPublicationFailure() {
        Object derbyTransaction = new Object();
        MvccInheritedStore store = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable accounts = openTable(store, 7, 701);
        MvccInheritedTable ledger = openTable(store, 7, 702);
        accounts.setPagePublicationHookForTesting((stage, changes) -> {
            if (stage == io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore
                    .PublicationStage.OUTCOME_FENCE) {
                throw new IllegalStateException("injected first-participant publication failure");
            }
        });

        DelosStorageTransaction accountsTx = accounts.beginTransaction();
        DelosStorageTransaction ledgerTx = ledger.beginTransaction();
        DelosStorageTransactionRegistry.register(derbyTransaction, accounts, accountsTx);
        DelosStorageTransactionRegistry.register(derbyTransaction, ledger, ledgerTx);
        accounts.insert(1L, durableEmptyRow(), accountsTx);
        ledger.insert(1L, durableEmptyRow(), ledgerTx);

        assertThrows(
                MvccDatabaseCommitCoordinator.DatabaseCommitRecoveryRequiredException.class,
                () -> DelosStorageTransactionRegistry.commit(derbyTransaction));
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        store.close();

        MvccInheritedStore reopenedStore = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable reopenedAccounts = openTable(reopenedStore, 7, 701);
        MvccInheritedTable reopenedLedger = openTable(reopenedStore, 7, 702);
        assertTrue(read(reopenedAccounts, 1L).isPresent());
        assertTrue(read(reopenedLedger, 1L).isPresent());
        reopenedStore.close();
    }

    @Test
    void participantConflictAbortsEveryStagedTableBeforeDatabaseDecision() {
        Object derbyTransaction = new Object();
        MvccInheritedStore store = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable accounts = openTable(store, 8, 801);
        MvccInheritedTable ledger = openTable(store, 8, 802);

        DelosStorageTransaction competingLedgerTx = ledger.beginTransaction();
        ledger.insert(1L, durableEmptyRow(), competingLedgerTx);

        DelosStorageTransaction accountsTx = accounts.beginTransaction();
        DelosStorageTransaction ledgerTx = ledger.beginTransaction();
        DelosStorageTransactionRegistry.register(derbyTransaction, accounts, accountsTx);
        DelosStorageTransactionRegistry.register(derbyTransaction, ledger, ledgerTx);
        accounts.insert(1L, durableEmptyRow(), accountsTx);
        ledger.insert(1L, durableEmptyRow(), ledgerTx);

        assertThrows(
                io.github.ggeorg.delosdb.storage.mvcc.MvccWriteConflictException.class,
                () -> DelosStorageTransactionRegistry.commit(derbyTransaction));
        ledger.abort(competingLedgerTx);
        store.close();

        MvccInheritedStore reopenedStore = new MvccInheritedStore(databaseDirectory);
        MvccInheritedTable reopenedAccounts = openTable(reopenedStore, 8, 801);
        MvccInheritedTable reopenedLedger = openTable(reopenedStore, 8, 802);
        assertEquals(0, reopenedAccounts.logicalRowCountForTesting());
        assertEquals(0, reopenedLedger.logicalRowCountForTesting());
        reopenedStore.close();
    }

    @Test
    void oneDerbyTransactionAbortsTwoMvccTablesAndPersistsNeither() {
        Object derbyTransaction = new Object();
        MvccInheritedTable accounts = table(2, 201);
        MvccInheritedTable ledger = table(2, 202);

        DelosStorageTransaction accountsTx = accounts.beginTransaction();
        DelosStorageTransaction ledgerTx = ledger.beginTransaction();
        DelosStorageTransactionRegistry.register(derbyTransaction, accounts, accountsTx);
        DelosStorageTransactionRegistry.register(derbyTransaction, ledger, ledgerTx);

        accounts.insert(1L, durableEmptyRow(), accountsTx);
        ledger.insert(1L, durableEmptyRow(), ledgerTx);

        DelosStorageTransactionRegistry.abort(derbyTransaction);
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        accounts.close();
        ledger.close();

        MvccInheritedTable reopenedAccounts = table(2, 201);
        MvccInheritedTable reopenedLedger = table(2, 202);

        assertTrue(read(reopenedAccounts, 1L).isEmpty());
        assertTrue(read(reopenedLedger, 1L).isEmpty());
        assertEquals(0, reopenedAccounts.logicalRowCountForTesting());
        assertEquals(0, reopenedLedger.logicalRowCountForTesting());
        reopenedAccounts.close();
        reopenedLedger.close();
    }

    @Test
    void registeredRollbackDoesNotDisturbPreviouslyCommittedState() {
        Object firstDerbyTransaction = new Object();
        MvccInheritedTable table = table(3, 301);
        DelosStorageTransaction first = table.beginTransaction();
        DelosStorageTransactionRegistry.register(firstDerbyTransaction, table, first);
        table.insert(1L, durableEmptyRow(), first);
        DelosStorageTransactionRegistry.commit(firstDerbyTransaction);

        Object secondDerbyTransaction = new Object();
        DelosStorageTransaction second = table.beginTransaction();
        DelosStorageSnapshot secondSnapshot = table.snapshot(second);
        DelosStorageTransactionRegistry.register(secondDerbyTransaction, table, second);
        table.update(1L, durableEmptyRow(), second, secondSnapshot);
        table.insert(2L, durableEmptyRow(), second);
        DelosStorageTransactionRegistry.abort(secondDerbyTransaction);
        table.close();

        MvccInheritedTable reopened = table(3, 301);
        assertTrue(read(reopened, 1L).isPresent());
        assertTrue(read(reopened, 2L).isEmpty());
        reopened.close();
    }


    @Test
    void registeredReadOnlyTransactionDoesNotGrowDurableStatusJournal() throws Exception {
        Object writerOwner = new Object();
        MvccInheritedTable table = table(4, 401);
        DelosStorageTransaction writer = table.beginTransaction();
        DelosStorageTransactionRegistry.register(writerOwner, table, writer);
        table.insert(1L, durableEmptyRow(), writer);
        DelosStorageTransactionRegistry.commit(writerOwner);

        Path statusFile = PageVolumeMvccPaths.inheritedStoreDirectory(databaseDirectory)
                .resolve("conglomerate-4-401.txstatus");
        long statusBytesAfterWriter = Files.size(statusFile);

        Object readerOwner = new Object();
        DelosStorageTransactionRegistry.Reader reader =
                DelosStorageTransactionRegistry.reader(readerOwner, table);
        assertTrue(table.read(1L, reader.snapshot()).isPresent());
        DelosStorageTransactionRegistry.commit(readerOwner);

        assertEquals(statusBytesAfterWriter, Files.size(statusFile),
                "read-only registry lifecycle must not force ACTIVE/ABORTED status records");
        table.close();
    }

    @Test
    void staleSnapshotCannotAcquireCurrentCommittedOrderedIndexCandidates() {
        MvccInheritedTable table = table(5, 501);
        DelosStorageTransaction staleReader = table.beginReadOnlyTransaction();
        DelosStorageSnapshot staleSnapshot = table.snapshot(staleReader);

        DelosStorageTransaction writer = table.beginTransaction();
        table.insert(1L, durableEmptyRow(), writer);
        table.commit(writer);

        assertFalse(table.orderedIndexRowIdsFor(
                        staleSnapshot, 0, "DOK1|I|1").isPresent(),
                "snapshot validation and candidate derivation must share one commit-exclusion boundary");

        table.abort(staleReader);
        table.close();
    }

    @Test
    void registryCommitCompletesEveryParticipantAndPreservesAllFailures() {
        Object owner = new Object();
        TestCommitCoordinator coordinator = new TestCommitCoordinator();
        FailingLifecycleTable firstWriter =
                new CoordinatedFailingLifecycleTable(true, false, coordinator);
        FailingLifecycleTable secondWriter =
                new CoordinatedFailingLifecycleTable(true, false, coordinator);
        FailingLifecycleTable reader = new FailingLifecycleTable(false, true);

        DelosStorageTransactionRegistry.register(owner, firstWriter, firstWriter.beginTransaction());
        DelosStorageTransactionRegistry.register(owner, secondWriter, secondWriter.beginTransaction());
        DelosStorageTransactionRegistry.reader(owner, reader);

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> DelosStorageTransactionRegistry.commit(owner));

        assertEquals("commit failure", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length,
                "later writer and reader failures must be retained as suppressed failures");
        assertEquals(1, firstWriter.commitCount);
        assertEquals(1, secondWriter.commitCount,
                "a failing first provider must not prevent later providers from completing");
        assertEquals(1, reader.abortCount,
                "transaction-scoped readers must still close after writer failure");
        assertEquals(1, DelosStorageTransactionRegistry.pendingCountForTesting(owner),
                "the coordinator owns writer terminality; only the failed reader remains registered");

        reader.failAbort = false;
        DelosStorageTransactionRegistry.abort(owner);
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(owner));
        assertEquals(0, firstWriter.abortCount);
        assertEquals(0, secondWriter.abortCount);
        assertEquals(2, reader.abortCount);
    }

    @Test
    void registryAbortCompletesEveryParticipantAndPreservesAllFailures() {
        Object owner = new Object();
        FailingLifecycleTable firstWriter = new FailingLifecycleTable(false, true);
        FailingLifecycleTable secondWriter = new FailingLifecycleTable(false, true);
        FailingLifecycleTable reader = new FailingLifecycleTable(false, true);

        DelosStorageTransactionRegistry.register(owner, firstWriter, firstWriter.beginTransaction());
        DelosStorageTransactionRegistry.register(owner, secondWriter, secondWriter.beginTransaction());
        DelosStorageTransactionRegistry.reader(owner, reader);

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> DelosStorageTransactionRegistry.abort(owner));

        assertEquals("abort failure", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(1, firstWriter.abortCount);
        assertEquals(1, secondWriter.abortCount);
        assertEquals(1, reader.abortCount);
        assertEquals(3, DelosStorageTransactionRegistry.pendingCountForTesting(owner),
                "failed abort participants must remain registered for retry");

        firstWriter.failAbort = false;
        secondWriter.failAbort = false;
        reader.failAbort = false;
        DelosStorageTransactionRegistry.abort(owner);
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(owner));
        assertEquals(2, firstWriter.abortCount);
        assertEquals(2, secondWriter.abortCount);
        assertEquals(2, reader.abortCount);
    }


    @Test
    void physicalTableRetirementAbortsOnlyItsParticipants() {
        Object owner = new Object();
        FailingLifecycleTable retired = new FailingLifecycleTable(false, false);
        FailingLifecycleTable replacement = new FailingLifecycleTable(false, false);

        DelosStorageTransactionRegistry.register(owner, retired, retired.beginTransaction());
        DelosStorageTransactionRegistry.reader(owner, retired);
        DelosStorageTransactionRegistry.register(owner, replacement, replacement.beginTransaction());
        DelosStorageTransactionRegistry.reader(owner, replacement);

        assertEquals(4, DelosStorageTransactionRegistry.pendingCountForTesting(owner));
        DelosStorageTransactionRegistry.abortTableParticipants(retired);

        assertEquals(2, retired.abortCount,
                "retiring a physical table must release both its writer and reader snapshot");
        assertEquals(0, retired.commitCount);
        assertEquals(2, DelosStorageTransactionRegistry.pendingCountForTesting(owner),
                "replacement-table participants must remain enrolled in the Derby transaction");

        DelosStorageTransactionRegistry.commit(owner);

        assertEquals(1, replacement.commitCount);
        assertEquals(1, replacement.abortCount,
                "the replacement table reader must close at the normal Derby commit boundary");
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(owner));
    }

    @Test
    void failedPhysicalTableRetirementKeepsCleanupOwnershipForRetry() {
        Object owner = new Object();
        FailingLifecycleTable retired = new FailingLifecycleTable(false, true);

        DelosStorageTransactionRegistry.register(owner, retired, retired.beginTransaction());
        DelosStorageTransactionRegistry.reader(owner, retired);

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> DelosStorageTransactionRegistry.abortTableParticipants(retired));

        assertEquals("abort failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length,
                "retirement must attempt both the writer and reader participant");
        assertEquals(2, DelosStorageTransactionRegistry.pendingCountForTesting(owner),
                "failed participants must remain registered for an explicit retry");

        retired.failAbort = false;
        DelosStorageTransactionRegistry.abortTableParticipants(retired);
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(owner));
        assertEquals(4, retired.abortCount,
                "each participant must be attempted once per retirement attempt");
    }

    @Test
    void failedReaderSnapshotCreationAbortsTheUnregisteredReadOnlyTransaction() {
        Object owner = new Object();
        FailingLifecycleTable table = new FailingLifecycleTable(false, false);
        table.failSnapshot = true;

        assertThrows(IllegalStateException.class,
                () -> DelosStorageTransactionRegistry.reader(owner, table));

        assertEquals(1, table.abortCount,
                "a read-only transaction whose snapshot cannot be created must be aborted");
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(owner));
    }

    @Test
    void failedSavepointEnrollmentAbortsTheUnregisteredWriter() {
        Object owner = new Object();
        FailingLifecycleTable table = new FailingLifecycleTable(false, false);
        table.failSavepoint = true;
        DelosStorageTransactionRegistry.setSavepoint(owner, "before-registration");

        assertThrows(IllegalStateException.class,
                () -> DelosStorageTransactionRegistry.register(
                        owner, table, table.beginTransaction()));

        assertEquals(1, table.setSavepointCount);
        assertEquals(1, table.abortCount,
                "a writer that cannot join existing savepoints must not leak outside the registry");
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(owner));
    }

    private void writeRawStoreDecision(DelosDatabaseCommitDecision decision) throws Exception {
        Path marker = DelosDatabaseCommitDecision.markerFile(
                databaseDirectory, decision.transactionId(), decision.commitSequence());
        Files.createDirectories(marker.getParent());
        Files.write(marker, decision.encoded());
    }

    private static final class CapturingRawStoreParticipant
            implements DelosRawStoreCommitParticipant {
        private DelosDatabaseCommitDecision decision;

        @Override
        public void stageDatabaseCommitDecision(DelosDatabaseCommitDecision stagedDecision)
                throws StandardException {
            decision = stagedDecision;
        }
    }

    private static MvccInheritedTable openTable(
            MvccInheritedStore store,
            long segmentId,
            long containerId) {
        return (MvccInheritedTable) store.openTable(
                new DelosStorageTableKey(segmentId, containerId));
    }

    private MvccInheritedTable table(long segmentId, long containerId) {
        return new MvccInheritedTable(segmentId, containerId, databaseDirectory);
    }

    private static StoreDataValue[] durableEmptyRow() {
        // This proof is about Derby transaction registry commit/abort/reopen
        // coordination. Use a typed-codec durable empty row so the test does
        // not revive the old arbitrary StoreValueOperations fixture and does
        // not accidentally test null-key index behavior.
        return new StoreDataValue[0];
    }

    private static Optional<StoreDataValue[]> read(MvccInheritedTable table, long rowId) {
        DelosStorageTransaction reader = table.beginReadOnlyTransaction();
        try {
            return table.read(rowId, table.snapshot(reader));
        } finally {
            table.abort(reader);
        }
    }

    private static class FailingLifecycleTable
            implements DelosStorageTable, DelosStorageSavepointParticipant {
        private boolean failCommit;
        private boolean failAbort;
        private boolean failSnapshot;
        private boolean failSavepoint;
        private int commitCount;
        private int abortCount;
        private int setSavepointCount;

        private FailingLifecycleTable(boolean failCommit, boolean failAbort) {
            this.failCommit = failCommit;
            this.failAbort = failAbort;
        }

        @Override
        public DelosStorageTransaction beginTransaction() {
            return new TestTransaction();
        }

        @Override
        public DelosStorageSnapshot snapshot(DelosStorageTransaction transaction) {
            if (failSnapshot) {
                throw new IllegalStateException("snapshot failure");
            }
            return new TestSnapshot();
        }

        @Override
        public DelosStorageScan openScan(DelosStorageSnapshot snapshot) throws StandardException {
            throw new UnsupportedOperationException("not required by transaction-registry proof");
        }

        @Override
        public Optional<StoreDataValue[]> read(long rowId, DelosStorageSnapshot snapshot) {
            return Optional.empty();
        }

        @Override
        public void insert(long rowId, StoreDataValue[] row, DelosStorageTransaction transaction) {
        }

        @Override
        public void update(
                long rowId,
                StoreDataValue[] replacement,
                DelosStorageTransaction transaction,
                DelosStorageSnapshot snapshot) {
        }

        @Override
        public void delete(
                long rowId,
                DelosStorageTransaction transaction,
                DelosStorageSnapshot snapshot) {
        }

        @Override
        public void commit(DelosStorageTransaction transaction) {
            commitCount++;
            if (failCommit) {
                throw new IllegalStateException("commit failure");
            }
        }

        @Override
        public void abort(DelosStorageTransaction transaction) {
            abortCount++;
            if (failAbort) {
                throw new IllegalStateException("abort failure");
            }
        }

        @Override
        public long nextRowId() {
            return 1L;
        }

        @Override
        public void setSavepoint(DelosStorageTransaction transaction, String savepointName) {
            setSavepointCount++;
            if (failSavepoint) {
                throw new IllegalStateException("savepoint failure");
            }
        }

        @Override
        public void rollbackToSavepoint(DelosStorageTransaction transaction, String savepointName) {
        }

        @Override
        public void releaseSavepoint(DelosStorageTransaction transaction, String savepointName) {
        }

        @Override
        public void close() {
        }
    }

    private static final class CoordinatedFailingLifecycleTable
            extends FailingLifecycleTable implements DelosStorageCoordinatedCommitTable {
        private final DelosStorageCommitCoordinator coordinator;

        private CoordinatedFailingLifecycleTable(
                boolean failCommit,
                boolean failAbort,
                DelosStorageCommitCoordinator coordinator) {
            super(failCommit, failAbort);
            this.coordinator = coordinator;
        }

        @Override
        public DelosStorageCommitCoordinator commitCoordinator() {
            return coordinator;
        }
    }

    private static final class TestCommitCoordinator implements DelosStorageCommitCoordinator {
        @Override
        public void commit(List<Participant> participants) {
            Throwable failure = null;
            for (Participant participant : participants) {
                try {
                    participant.table().commit(participant.transaction());
                } catch (RuntimeException | Error participantFailure) {
                    if (failure == null) {
                        failure = participantFailure;
                    } else {
                        failure.addSuppressed(participantFailure);
                    }
                }
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error errorFailure) {
                throw errorFailure;
            }
        }
    }

    private record TestTransaction() implements DelosStorageTransaction {
        @Override
        public String providerName() {
            return "test";
        }

        @Override
        public Object nativeTransaction() {
            return this;
        }
    }

    private record TestSnapshot() implements DelosStorageSnapshot {
        @Override
        public String providerName() {
            return "test";
        }

        @Override
        public Object nativeSnapshot() {
            return this;
        }
    }
}
