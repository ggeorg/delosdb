package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.derby.iapi.store.types.DelosDatabaseCommitDecision;
import org.apache.derby.iapi.store.types.DelosMvccConglomerateLifecycle;
import org.apache.derby.iapi.store.types.DelosRawStoreCommitParticipant;
import org.apache.derby.iapi.store.types.DelosStorageTableKey;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.shared.common.error.StandardException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatus;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccPaths;

/** Bounded mixed-decision marker and database-status retention proof. */
final class MvccDatabaseDecisionRetentionTest {
    @TempDir
    Path root;

    @AfterEach
    void clearRegistry() {
        DelosStorageTransactionRegistry.clearForTesting();
    }

    @Test
    void successfulMixedCommitRetiresMarkerAfterDurableMirror() throws Exception {
        Path database = root.resolve("mixed-success");
        MvccInheritedStore store = new MvccInheritedStore(database, 1L);
        RawScenario scenario = prepareMixed(store, 101L, 102L, 1L);
        writeRawStoreDecision(database, scenario.rawStore().decision);

        DelosStorageTransactionRegistry.completeCommit(scenario.preparation());

        assertEquals(0, store.transactionCoordinatorForTesting()
                .retainedDecisionMarkerCountForTesting());
        assertEquals(0L, store.transactionCoordinatorForTesting()
                .decisionRetentionFailureCountForTesting());
        store.close();

        assertCommittedRows(database, 101L, 102L, 1L);
        assertEquals(0, decisionMarkerCount(database));
    }

    @Test
    void reopenMirrorsAndRetiresMarkerBeforeParticipantRecovery() throws Exception {
        Path database = root.resolve("mixed-reopen");
        MvccInheritedStore store = new MvccInheritedStore(database, 1L);
        RawScenario scenario = prepareMixed(store, 201L, 202L, 1L);
        writeRawStoreDecision(database, scenario.rawStore().decision);
        DelosStorageTransactionRegistry.releasePreparedCommitForRecovery(
                scenario.preparation());
        store.close();
        assertEquals(1, decisionMarkerCount(database));

        MvccInheritedStore reopened = new MvccInheritedStore(database, 1L);
        assertEquals(0, reopened.transactionCoordinatorForTesting()
                .retainedDecisionMarkerCountForTesting());
        assertTrue(reopened.transactionCoordinatorForTesting()
                .recoveredStatuses()
                .values()
                .stream()
                .anyMatch(status -> status.status() == MvccTransactionStatus.COMMITTED));
        MvccInheritedTable first = openTable(reopened, 201L);
        MvccInheritedTable second = openTable(reopened, 202L);
        assertTrue(read(first, 1L).isPresent());
        assertTrue(read(second, 1L).isPresent());
        reopened.close();
        assertEquals(0, decisionMarkerCount(database));
    }

    @Test
    void statusJournalCompactsToWatermarksAfterResolvedCommits() throws Exception {
        Path database = root.resolve("bounded-status");
        MvccInheritedStore store = new MvccInheritedStore(database, 1L);
        MvccInheritedTable first = openTable(store, 301L);
        MvccInheritedTable second = openTable(store, 302L);

        for (long rowId = 1L; rowId <= 40L; rowId++) {
            commitTwoTables(first, second, rowId);
        }

        Path statusFile = PageVolumeMvccPaths.databaseTransactionStatusFile(database);
        List<String> compacted = Files.readAllLines(statusFile);
        assertTrue(compacted.size() <= 2,
                "resolved decision history must compact to bounded watermarks: " + compacted);
        assertTrue(Files.size(statusFile) < 256L,
                "bounded status journal should not grow with lifetime commit count");
        assertTrue(read(first, 40L).isPresent());
        assertEquals(0L, store.transactionCoordinatorForTesting()
                .decisionRetentionFailureCountForTesting());
        store.close();

        MvccInheritedStore reopened = new MvccInheritedStore(database, 1L);
        MvccInheritedTable reopenedFirst = openTable(reopened, 301L);
        MvccInheritedTable reopenedSecond = openTable(reopened, 302L);
        assertTrue(read(reopenedFirst, 1L).isPresent());
        assertTrue(read(reopenedFirst, 40L).isPresent());
        assertTrue(read(reopenedSecond, 40L).isPresent());
        reopened.close();
        assertTrue(Files.readAllLines(statusFile).size() <= 2);
    }

    @Test
    void finalTableDropRemovesDatabaseDecisionJournalAndEmptyDirectories() {
        Path database = root.resolve("last-table-drop");
        Path statusFile = PageVolumeMvccPaths.databaseTransactionStatusFile(database);
        MvccInheritedStore store = new MvccInheritedStore(database, 1L);
        MvccInheritedTable first = openTable(store, 351L);
        MvccInheritedTable second = openTable(store, 352L);
        commitTwoTables(first, second, 1L);
        assertTrue(Files.exists(statusFile));

        first.dropDurableState();
        assertTrue(Files.exists(statusFile),
                "database decision state must remain while another table exists");

        second.dropDurableState();
        assertTrue(!Files.exists(statusFile),
                "dropping the final MVCC table must remove obsolete database decision state");
        assertEquals(0L, store.transactionCoordinatorForTesting()
                .decisionRetentionFailureCountForTesting());
        store.close();

        MvccInheritedStore reopened = new MvccInheritedStore(database, 1L);
        reopened.close();
        assertTrue(!Files.exists(statusFile),
                "opening an empty MVCC runtime must not recreate an empty status journal");
    }

    @Test
    void unresolvedPreparedCorrelationSurvivesCompactionAndReopen() {
        Path database = root.resolve("unresolved-publication");
        MvccFailurePointRegistry registry = MvccFailurePointRegistry.scheduled(
                database,
                MvccFailurePointRegistry.Schedule.of(
                        "retention-before-publication",
                        MvccFailurePointRegistry.Step.fail(
                                MvccFailurePointRegistry.Point
                                        .BEFORE_FIRST_PARTICIPANT_PUBLICATION)));
        MvccInheritedStore store = new MvccInheritedStore(database, registry, 1L);
        Object owner = writeTwoTables(store, 401L, 402L, 1L);

        assertThrows(
                MvccDatabaseCommitCoordinator.DatabaseCommitRecoveryRequiredException.class,
                () -> DelosStorageTransactionRegistry.commit(owner));
        assertTrue(store.transactionCoordinatorForTesting()
                .recoveredStatuses()
                .values()
                .stream()
                .anyMatch(status -> status.status() == MvccTransactionStatus.COMMITTED));
        store.close();

        assertCommittedRows(database, 401L, 402L, 1L);
        Path statusFile = PageVolumeMvccPaths.databaseTransactionStatusFile(database);
        assertTrue(Files.exists(statusFile));
        MvccTransactionStatusStore statuses = MvccTransactionStatusStore.open(statusFile);
        assertTrue(statuses.recoverStatuses().values().stream()
                .anyMatch(status -> status.status() == MvccTransactionStatus.COMMITTED));
    }

    private RawScenario prepareMixed(
            MvccInheritedStore store,
            long firstContainer,
            long secondContainer,
            long rowId) throws StandardException {
        Object owner = writeTwoTables(store, firstContainer, secondContainer, rowId);
        DelosStorageTransactionRegistry.registerWriteIntent(owner, false, false);
        DelosStorageTransactionRegistry.registerWriteIntent(owner, true, false);
        DelosStorageTransactionRegistry.registerWriteIntent(owner, true, false);
        CapturingRawStoreParticipant rawStore = new CapturingRawStoreParticipant();
        DelosStorageTransactionRegistry.CommitPreparation preparation =
                DelosStorageTransactionRegistry.prepareCommit(owner, rawStore);
        return new RawScenario(rawStore, preparation);
    }

    private Object writeTwoTables(
            MvccInheritedStore store,
            long firstContainer,
            long secondContainer,
            long rowId) {
        MvccInheritedTable first = openTable(store, firstContainer);
        MvccInheritedTable second = openTable(store, secondContainer);
        Object owner = new Object();
        DelosStorageTransaction firstTx = first.beginTransaction();
        DelosStorageTransaction secondTx = second.beginTransaction();
        DelosStorageTransactionRegistry.register(owner, first, firstTx);
        DelosStorageTransactionRegistry.register(owner, second, secondTx);
        first.insert(rowId, emptyRow(), firstTx);
        second.insert(rowId, emptyRow(), secondTx);
        return owner;
    }

    private void commitTwoTables(
            MvccInheritedTable first,
            MvccInheritedTable second,
            long rowId) {
        Object owner = new Object();
        DelosStorageTransaction firstTx = first.beginTransaction();
        DelosStorageTransaction secondTx = second.beginTransaction();
        DelosStorageTransactionRegistry.register(owner, first, firstTx);
        DelosStorageTransactionRegistry.register(owner, second, secondTx);
        first.insert(rowId, emptyRow(), firstTx);
        second.insert(rowId, emptyRow(), secondTx);
        DelosStorageTransactionRegistry.commit(owner);
    }

    private void assertCommittedRows(
            Path database,
            long firstContainer,
            long secondContainer,
            long rowId) {
        MvccInheritedStore reopened = new MvccInheritedStore(database, 1L);
        try {
            assertTrue(read(openTable(reopened, firstContainer), rowId).isPresent());
            assertTrue(read(openTable(reopened, secondContainer), rowId).isPresent());
        } finally {
            reopened.close();
        }
    }

    private static MvccInheritedTable openTable(
            MvccInheritedStore store,
            long containerId) {
        return (MvccInheritedTable) store.openTable(
                new DelosStorageTableKey(1L, containerId));
    }

    private static Optional<StoreDataValue[]> read(
            MvccInheritedTable table,
            long rowId) {
        DelosStorageTransaction transaction = table.beginReadOnlyTransaction();
        try {
            return table.read(rowId, table.snapshot(transaction));
        } finally {
            table.abort(transaction);
        }
    }

    private static StoreDataValue[] emptyRow() {
        return new StoreDataValue[0];
    }

    private static void writeRawStoreDecision(
            Path databaseDirectory,
            DelosDatabaseCommitDecision decision) throws Exception {
        Path marker = DelosDatabaseCommitDecision.markerFile(
                databaseDirectory,
                decision.transactionId(),
                decision.commitSequence());
        Files.createDirectories(marker.getParent());
        Files.write(marker, decision.encoded());
    }

    private static int decisionMarkerCount(Path databaseDirectory) throws Exception {
        Path directory = DelosDatabaseCommitDecision.directory(databaseDirectory);
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var markers = Files.newDirectoryStream(directory, "commit-*.decision")) {
            int count = 0;
            for (Path ignored : markers) {
                count++;
            }
            return count;
        }
    }

    private record RawScenario(
            CapturingRawStoreParticipant rawStore,
            DelosStorageTransactionRegistry.CommitPreparation preparation) {
    }

    private static final class CapturingRawStoreParticipant
            implements DelosRawStoreCommitParticipant {
        private DelosDatabaseCommitDecision decision;

        @Override
        public void stageDatabaseCommitDecision(DelosDatabaseCommitDecision stagedDecision)
                throws StandardException {
            decision = stagedDecision;
        }

        @Override
        public void stageMvccConglomerateLifecycle(DelosMvccConglomerateLifecycle lifecycle) {
        }
    }
}
