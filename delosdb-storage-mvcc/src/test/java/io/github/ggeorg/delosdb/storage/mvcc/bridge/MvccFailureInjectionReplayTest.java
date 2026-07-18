package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccFailureReplayTestSupport.committedDigest;
import static io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccFailureReplayTestSupport.emptyDigest;
import static io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccFailureReplayTestSupport.reopenedDigest;
import static io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccFailureReplayTestSupport.writeTwoTables;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.derby.iapi.store.types.DelosDatabaseCommitDecision;
import org.apache.derby.iapi.store.types.DelosMvccConglomerateLifecycle;
import org.apache.derby.iapi.store.types.DelosRawStoreCommitParticipant;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.shared.common.error.StandardException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 8.4 transaction failure-point, manifest, and replay foundation. */
final class MvccFailureInjectionReplayTest {
    @TempDir
    Path root;

    @AfterEach
    void clearRegistry() {
        DelosStorageTransactionRegistry.clearForTesting();
    }

    @Test
    void preDecisionFailuresAbortEveryPreparedParticipant() {
        assertPreDecisionAbort(
                "after-first-prepare",
                MvccFailurePointRegistry.Step.fail(
                        MvccFailurePointRegistry.Point.AFTER_PARTICIPANT_PREPARE, 1L));
        assertPreDecisionAbort(
                "before-decision-force",
                MvccFailurePointRegistry.Step.fail(
                        MvccFailurePointRegistry.Point.BEFORE_TRANSACTION_DECISION_FORCE));
    }

    @Test
    void committedDecisionFailuresReplayToOneFinalDigest() {
        String expected = committedDigest();
        for (MvccFailurePointRegistry.Step step : List.of(
                MvccFailurePointRegistry.Step.fail(
                        MvccFailurePointRegistry.Point.AFTER_TRANSACTION_DECISION_FORCE),
                MvccFailurePointRegistry.Step.fail(
                        MvccFailurePointRegistry.Point.BEFORE_FIRST_PARTICIPANT_PUBLICATION),
                MvccFailurePointRegistry.Step.fail(
                        MvccFailurePointRegistry.Point.BETWEEN_PARTICIPANT_PUBLICATIONS))) {
            Path database = root.resolve(step.point().name().toLowerCase());
            MvccFailurePointRegistry.Schedule schedule =
                    MvccFailurePointRegistry.Schedule.of("post-decision", step);
            runMvccOnlyFailure(database, schedule);
            assertEquals(expected, reopenedDigest(database));
            assertEquals(expected, reopenedDigest(database),
                    "repeated reopen must remain idempotent");
        }
    }

    @Test
    void rawStoreBoundariesDistinguishAbortFromCommittedRecovery() throws Exception {
        Path beforeDatabase = root.resolve("before-raw-store");
        MvccFailurePointRegistry beforeRegistry = MvccFailurePointRegistry.scheduled(
                beforeDatabase,
                MvccFailurePointRegistry.Schedule.of(
                        "before-raw-store",
                        MvccFailurePointRegistry.Step.fail(
                                MvccFailurePointRegistry.Point.BEFORE_DERBY_RAW_STORE_COMMIT)));
        MvccInheritedStore beforeStore = new MvccInheritedStore(beforeDatabase, beforeRegistry);
        RawScenario before = prepareMixed(beforeStore);
        assertThrows(
                MvccFailurePointRegistry.InjectedFailure.class,
                () -> DelosStorageTransactionRegistry.beforeRawStoreCommit(before.preparation()));
        DelosStorageTransactionRegistry.abortPreparedCommit(before.preparation());
        beforeStore.close();
        assertEquals(emptyDigest(), reopenedDigest(beforeDatabase));

        Path afterDatabase = root.resolve("after-raw-store");
        MvccFailurePointRegistry afterRegistry = MvccFailurePointRegistry.scheduled(
                afterDatabase,
                MvccFailurePointRegistry.Schedule.of(
                        "after-raw-store",
                        MvccFailurePointRegistry.Step.fail(
                                MvccFailurePointRegistry.Point.AFTER_DERBY_RAW_STORE_COMMIT)));
        MvccInheritedStore afterStore = new MvccInheritedStore(afterDatabase, afterRegistry);
        RawScenario after = prepareMixed(afterStore);
        DelosStorageTransactionRegistry.beforeRawStoreCommit(after.preparation());
        writeRawStoreDecision(afterDatabase, after.rawStore().decision);
        assertThrows(
                MvccDatabaseCommitCoordinator.DatabaseCommitRecoveryRequiredException.class,
                () -> DelosStorageTransactionRegistry.afterRawStoreCommit(after.preparation()));
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(after.owner()));
        afterStore.close();
        assertEquals(committedDigest(), reopenedDigest(afterDatabase));
    }

    @Test
    void ambiguousDurableRawStoreCommitReleasesOwnershipForRecovery() throws Exception {
        Path database = root.resolve("ambiguous-raw-store");
        MvccInheritedStore store = new MvccInheritedStore(database);
        RawScenario scenario = prepareMixed(store);

        writeRawStoreDecision(database, scenario.rawStore().decision);
        DelosStorageTransactionRegistry.releasePreparedCommitForRecovery(scenario.preparation());

        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(scenario.owner()));
        store.close();
        assertEquals(committedDigest(), reopenedDigest(database));
        assertEquals(committedDigest(), reopenedDigest(database));
    }

    @Test
    void seededBarrierHaltAndManifestReplayAreDeterministic() {
        List<MvccFailurePointRegistry.Point> candidates = List.of(
                MvccFailurePointRegistry.Point.BEFORE_TRANSACTION_DECISION_FORCE,
                MvccFailurePointRegistry.Point.AFTER_TRANSACTION_DECISION_FORCE,
                MvccFailurePointRegistry.Point.BEFORE_FIRST_PARTICIPANT_PUBLICATION,
                MvccFailurePointRegistry.Point.BETWEEN_PARTICIPANT_PUBLICATIONS);
        MvccFailurePointRegistry.Schedule first =
                MvccFailurePointRegistry.Schedule.seeded("seeded", 884L, candidates);
        MvccFailurePointRegistry.Schedule second =
                MvccFailurePointRegistry.Schedule.seeded("seeded", 884L, candidates);
        assertEquals(first, second);

        AtomicInteger barrierArrivals = new AtomicInteger();
        MvccFailurePointRegistry.Step barrierStep = MvccFailurePointRegistry.Step.fail(
                        MvccFailurePointRegistry.Point.BEFORE_TRANSACTION_DECISION_FORCE)
                .withBarrier(hit -> barrierArrivals.incrementAndGet());
        Path barrierDatabase = root.resolve("barrier");
        runMvccOnlyFailure(
                barrierDatabase,
                MvccFailurePointRegistry.Schedule.of("barrier", barrierStep));
        assertEquals(1, barrierArrivals.get());
        assertEquals(emptyDigest(), reopenedDigest(barrierDatabase));

        AtomicInteger haltStatus = new AtomicInteger();
        MvccFailurePointRegistry haltRegistry = MvccFailurePointRegistry.scheduled(
                root.resolve("halt"),
                MvccFailurePointRegistry.Schedule.of(
                        "halt",
                        MvccFailurePointRegistry.Step.halt(
                                MvccFailurePointRegistry.Point.DURING_CHECKPOINT,
                                1L,
                                84)),
                status -> {
                    haltStatus.set(status);
                    throw new SimulatedProcessHalt();
                });
        assertThrows(
                SimulatedProcessHalt.class,
                () -> haltRegistry.hit(
                        MvccFailurePointRegistry.Point.DURING_CHECKPOINT,
                        MvccFailurePointRegistry.Context.transaction(7L, 9L, 0, 0)));
        assertEquals(84, haltStatus.get());

        Path sourceDatabase = root.resolve("manifest-source");
        MvccFailurePointRegistry.Schedule replaySchedule =
                MvccFailurePointRegistry.Schedule.of(
                        "manifest-replay",
                        MvccFailurePointRegistry.Step.fail(
                                MvccFailurePointRegistry.Point.BETWEEN_PARTICIPANT_PUBLICATIONS));
        runMvccOnlyFailure(sourceDatabase, replaySchedule);
        String observed = reopenedDigest(sourceDatabase);
        Path manifestPath = root.resolve("experiment.manifest");
        new MvccFailureExperimentManifest(
                "phase-8.4-transaction-replay",
                sourceDatabase.toAbsolutePath().normalize().toString(),
                replaySchedule,
                "a committed database decision reopens with every participant visible",
                committedDigest(),
                observed).write(manifestPath);

        MvccFailureExperimentManifest manifest =
                MvccFailureExperimentManifest.read(manifestPath);
        assertEquals(committedDigest(), manifest.expectedFinalStateDigest());
        assertEquals(committedDigest(), manifest.observedFinalStateDigest());

        Path replayDatabase = root.resolve("manifest-replay");
        runMvccOnlyFailure(replayDatabase, manifest.schedule());
        assertEquals(manifest.expectedFinalStateDigest(), reopenedDigest(replayDatabase));
    }

    private void assertPreDecisionAbort(String name, MvccFailurePointRegistry.Step step) {
        Path database = root.resolve(name);
        MvccFailurePointRegistry.Schedule schedule =
                MvccFailurePointRegistry.Schedule.of(name, step);
        MvccFailurePointRegistry registry =
                MvccFailurePointRegistry.scheduled(database, schedule);
        MvccInheritedStore store = new MvccInheritedStore(database, registry);
        Object owner = writeTwoTables(store);
        assertThrows(
                MvccFailurePointRegistry.InjectedFailure.class,
                () -> DelosStorageTransactionRegistry.commit(owner));
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(owner));
        store.close();
        assertEquals(emptyDigest(), reopenedDigest(database));
    }

    private void runMvccOnlyFailure(
            Path database,
            MvccFailurePointRegistry.Schedule schedule) {
        MvccFailurePointRegistry registry =
                MvccFailurePointRegistry.scheduled(database, schedule);
        MvccInheritedStore store = new MvccInheritedStore(database, registry);
        Object owner = writeTwoTables(store);
        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> DelosStorageTransactionRegistry.commit(owner));
        MvccFailurePointRegistry.Point point = schedule.steps().getFirst().point();
        if (point == MvccFailurePointRegistry.Point.AFTER_TRANSACTION_DECISION_FORCE
                || point == MvccFailurePointRegistry.Point.BEFORE_FIRST_PARTICIPANT_PUBLICATION
                || point == MvccFailurePointRegistry.Point.BETWEEN_PARTICIPANT_PUBLICATIONS) {
            assertTrue(failure instanceof
                    MvccDatabaseCommitCoordinator.DatabaseCommitRecoveryRequiredException);
        }
        store.close();
    }

    private RawScenario prepareMixed(MvccInheritedStore store) throws StandardException {
        Object owner = writeTwoTables(store);
        DelosStorageTransactionRegistry.registerWriteIntent(owner, false, false);
        DelosStorageTransactionRegistry.registerWriteIntent(owner, true, false);
        DelosStorageTransactionRegistry.registerWriteIntent(owner, true, false);
        CapturingRawStoreParticipant rawStore = new CapturingRawStoreParticipant();
        DelosStorageTransactionRegistry.CommitPreparation preparation =
                DelosStorageTransactionRegistry.prepareCommit(owner, rawStore);
        return new RawScenario(owner, rawStore, preparation);
    }

    private static void writeRawStoreDecision(
            Path databaseDirectory,
            DelosDatabaseCommitDecision decision) throws Exception {
        Path marker = DelosDatabaseCommitDecision.markerFile(
                databaseDirectory, decision.transactionId(), decision.commitSequence());
        Files.createDirectories(marker.getParent());
        Files.write(marker, decision.encoded());
    }

    private record RawScenario(
            Object owner,
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

    private static final class SimulatedProcessHalt extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
