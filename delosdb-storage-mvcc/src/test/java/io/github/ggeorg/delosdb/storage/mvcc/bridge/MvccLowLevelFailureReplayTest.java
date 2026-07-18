package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccFailureReplayTestSupport.committedDigest;
import static io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccFailureReplayTestSupport.reopenedDigest;
import static io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccFailureReplayTestSupport.writeTwoTables;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccVacuumPlan;
import io.github.ggeorg.delosdb.storage.mvcc.durable.PageBackedMvccTable;
import io.github.ggeorg.delosdb.storage.mvcc.failure.MvccStorageFailureHook;

/** Phase 8.4 low-level storage fault points and real process-halt replay proof. */
final class MvccLowLevelFailureReplayTest {
    private static final int HALT_STATUS = 84;

    @TempDir
    Path root;

    @Test
    void pageAllocationOverflowAndVacuumReuseRecoverAfterCommittedFence()
            throws Exception {
        assertDirectMaterializationRecovery(
                "page-allocation",
                MvccFailurePointRegistry.Point.DURING_PAGE_ALLOCATION,
                "small");
        assertDirectMaterializationRecovery(
                "overflow-publication",
                MvccFailurePointRegistry.Point.DURING_OVERFLOW_PUBLICATION,
                "x".repeat(16_000));
        assertVacuumReuseRecovery();
    }

    @Test
    void checkpointAndIndexPublicationFailuresRecoverEveryParticipant() {
        assertInheritedRecovery(
                "checkpoint",
                MvccFailurePointRegistry.Point.DURING_CHECKPOINT);
        assertInheritedRecovery(
                "index-publication",
                MvccFailurePointRegistry.Point.DURING_INDEX_PUBLICATION);
    }

    @Test
    void checkpointProcessHaltReplaysFromManifestToOneDigest() throws Exception {
        MvccFailurePointRegistry.Schedule schedule =
                MvccFailurePointRegistry.Schedule.of(
                        "checkpoint-process-halt",
                        MvccFailurePointRegistry.Step.halt(
                                MvccFailurePointRegistry.Point.DURING_CHECKPOINT,
                                1L,
                                HALT_STATUS));

        Path sourceDatabase = root.resolve("process-halt-source");
        ProcessResult source = runCrashWorker(sourceDatabase, schedule);
        assertEquals(HALT_STATUS, source.exitCode(), source.output());
        String observed = reopenedDigest(sourceDatabase);
        assertEquals(committedDigest(), observed);
        assertEquals(committedDigest(), reopenedDigest(sourceDatabase));

        Path manifestPath = root.resolve("checkpoint-process-halt.manifest");
        new MvccFailureExperimentManifest(
                "phase-8.4-low-level-crash-replay",
                sourceDatabase.toAbsolutePath().normalize().toString(),
                schedule,
                "a committed checkpoint-interrupted transaction reopens with every participant visible",
                committedDigest(),
                observed).write(manifestPath);

        MvccFailureExperimentManifest manifest =
                MvccFailureExperimentManifest.read(manifestPath);
        Path replayDatabase = root.resolve("process-halt-replay");
        ProcessResult replay = runCrashWorker(replayDatabase, manifest.schedule());
        assertEquals(HALT_STATUS, replay.exitCode(), replay.output());
        assertEquals(manifest.expectedFinalStateDigest(), reopenedDigest(replayDatabase));
        assertEquals(manifest.expectedFinalStateDigest(), reopenedDigest(replayDatabase));
    }

    private void assertDirectMaterializationRecovery(
            String name,
            MvccFailurePointRegistry.Point point,
            String value) throws Exception {
        Path database = root.resolve(name);
        DurablePaths paths = durablePaths(database, name);
        MvccFailurePointRegistry registry = MvccFailurePointRegistry.scheduled(
                database,
                MvccFailurePointRegistry.Schedule.of(
                        name, MvccFailurePointRegistry.Step.fail(point)));
        MvccStorageFailureHook.Context context =
                MvccStorageFailureHook.Context.transaction(11L, 11L, 1, 1);

        try (PageBackedMvccTable table = PageBackedMvccTable.open(
                paths.table(), paths.mutationLog(), paths.outcomeLog())) {
            PageBackedMvccTable.PreparedTransaction prepared =
                    table.prepareCommittedTransaction(
                            11L,
                            11L,
                            List.of(PageBackedMvccTable.CommittedWrite.insert(
                                    "row:1",
                                    value.getBytes(StandardCharsets.UTF_8),
                                    DelosLogSequenceNumber.NONE)));
            table.stagePreparedTransaction(prepared);
            assertThrows(
                    PageBackedMvccTable.CommittedTransactionMaterializationException.class,
                    () -> table.publishPreparedTransaction(
                            prepared, registry.storageHook(), context));
        }

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(
                paths.table(), paths.mutationLog(), paths.outcomeLog())) {
            assertEquals(value, reopened.read(
                    "row:1", new MvccCommitSequence(11L)).orElseThrow());
            if (point == MvccFailurePointRegistry.Point.DURING_OVERFLOW_PUBLICATION) {
                assertTrue(reopened.overflowPageCount() > 0L);
            }
        }
        try (PageBackedMvccTable reopenedAgain = PageBackedMvccTable.open(
                paths.table(), paths.mutationLog(), paths.outcomeLog())) {
            assertEquals(value, reopenedAgain.read(
                    "row:1", new MvccCommitSequence(11L)).orElseThrow());
        }
        assertEquals(point, registry.hits().getFirst().point());
    }

    private void assertVacuumReuseRecovery() throws Exception {
        String name = "vacuum-reuse";
        Path database = root.resolve(name);
        DurablePaths paths = durablePaths(database, name);
        MvccFailurePointRegistry registry = MvccFailurePointRegistry.scheduled(
                database,
                MvccFailurePointRegistry.Schedule.of(
                        name,
                        MvccFailurePointRegistry.Step.fail(
                                MvccFailurePointRegistry.Point.DURING_VACUUM_REUSE)));
        String largeValue = "v".repeat(2_400);

        try (PageBackedMvccTable table = PageBackedMvccTable.open(
                paths.table(), paths.mutationLog(), paths.outcomeLog())) {
            table.insertCommitted("account:1", largeValue, 1L, 1L);
            table.insertCommitted("account:2", largeValue, 1L, 1L);
            for (int round = 2; round <= 7; round++) {
                table.updateCommitted(
                        "account:1", largeValue + round, round, round);
                table.updateCommitted(
                        "account:2", largeValue + round, round, round);
            }
            table.vacuum(MvccVacuumPlan.through(Long.MAX_VALUE));
            assertTrue(table.reusablePageCount() > 0L);

            String reuseValue = "r".repeat(2_400);
            PageBackedMvccTable.PreparedTransaction prepared =
                    table.prepareCommittedTransaction(
                            8L,
                            8L,
                            List.of(
                                    committedInsert("account:3", reuseValue),
                                    committedInsert("account:4", reuseValue),
                                    committedInsert("account:5", reuseValue),
                                    committedInsert("account:6", reuseValue),
                                    committedInsert("account:7", reuseValue),
                                    committedInsert("account:8", reuseValue)));
            table.stagePreparedTransaction(prepared);
            assertThrows(
                    PageBackedMvccTable.CommittedTransactionMaterializationException.class,
                    () -> table.publishPreparedTransaction(
                            prepared,
                            registry.storageHook(),
                            MvccStorageFailureHook.Context.transaction(8L, 8L, 1, 1)));
        }

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(
                paths.table(), paths.mutationLog(), paths.outcomeLog())) {
            assertEquals("r".repeat(2_400), reopened.read(
                    "account:3", new MvccCommitSequence(8L)).orElseThrow());
            assertEquals("r".repeat(2_400), reopened.read(
                    "account:8", new MvccCommitSequence(8L)).orElseThrow());
            reopened.validateConsistency().assertValid();
        }
        assertEquals(
                MvccFailurePointRegistry.Point.DURING_VACUUM_REUSE,
                registry.hits().getFirst().point());
    }


    private static PageBackedMvccTable.CommittedWrite committedInsert(
            String key,
            String value) {
        return PageBackedMvccTable.CommittedWrite.insert(
                key,
                value.getBytes(StandardCharsets.UTF_8),
                DelosLogSequenceNumber.NONE);
    }

    private void assertInheritedRecovery(
            String name,
            MvccFailurePointRegistry.Point point) {
        Path database = root.resolve(name);
        MvccFailurePointRegistry registry = MvccFailurePointRegistry.scheduled(
                database,
                MvccFailurePointRegistry.Schedule.of(
                        name, MvccFailurePointRegistry.Step.fail(point)));
        MvccInheritedStore store = new MvccInheritedStore(database, registry);
        Object owner = writeTwoTables(store);
        assertThrows(
                MvccDatabaseCommitCoordinator.DatabaseCommitRecoveryRequiredException.class,
                () -> DelosStorageTransactionRegistry.commit(owner));
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(owner));
        store.close();
        assertEquals(committedDigest(), reopenedDigest(database));
        assertEquals(committedDigest(), reopenedDigest(database));
        MvccFailurePointRegistry.Step scheduledStep = registry.schedule().steps().stream()
                .filter(step -> step.point() == point)
                .findFirst()
                .orElseThrow();
        assertTrue(
                registry.hits().stream().anyMatch(hit ->
                        hit.point() == point
                                && hit.occurrence() == scheduledStep.occurrence()),
                "scheduled failure point was not reached: " + point
                        + " occurrence " + scheduledStep.occurrence()
                        + "; hits=" + registry.hits());
    }

    private ProcessResult runCrashWorker(
            Path database,
            MvccFailurePointRegistry.Schedule schedule) throws Exception {
        MvccFailurePointRegistry.Step step = schedule.steps().getFirst();
        String javaExecutable = Path.of(
                System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database.toAbsolutePath().normalize().toString(),
                schedule.id(),
                step.point().name(),
                Long.toString(step.occurrence()),
                Integer.toString(step.haltStatus()))
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("MVCC crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private static DurablePaths durablePaths(Path database, String name)
            throws IOException {
        java.nio.file.Files.createDirectories(database);
        return new DurablePaths(
                database.resolve(name + ".dmvcc"),
                database.resolve(name + ".dmvcc.pagemut"),
                database.resolve(name + ".dmvcc.txoutcome"));
    }

    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] args) {
            if (args.length != 5) {
                System.err.println("expected database, schedule id, point, occurrence, halt status");
                System.exit(90);
            }
            Path database = Path.of(args[0]);
            String scheduleId = args[1];
            MvccFailurePointRegistry.Point point =
                    MvccFailurePointRegistry.Point.valueOf(args[2]);
            long occurrence = Long.parseLong(args[3]);
            int haltStatus = Integer.parseInt(args[4]);
            MvccFailurePointRegistry registry = MvccFailurePointRegistry.scheduled(
                    database,
                    MvccFailurePointRegistry.Schedule.of(
                            scheduleId,
                            MvccFailurePointRegistry.Step.halt(
                                    point, occurrence, haltStatus)));
            MvccInheritedStore store = new MvccInheritedStore(database, registry);
            Object owner = writeTwoTables(store);
            DelosStorageTransactionRegistry.commit(owner);
            System.err.println("commit returned without the configured process halt");
            System.exit(91);
        }
    }

    private record DurablePaths(Path table, Path mutationLog, Path outcomeLog) {
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
