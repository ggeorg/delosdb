package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.services.io.ArrayInputStream;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Failure, backup, and shutdown proofs for the Phase 7.5 group-commit boundary. */
final class MvccTransactionGroupCommitHardeningTest {
    private static final long GROUP_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);

    @TempDir
    Path databaseDirectory;

    @Test
    void sharedStatusForceFailureReachesLeaderAndFollowerBeforePagePublication() throws Exception {
        Path directory = databaseDirectory.resolve("status-force-failure");
        Files.createDirectories(directory);
        MvccInheritedTable table = new MvccInheritedTable(
                0L,
                901L,
                directory,
                MvccCommitCoordinator.Mode.GROUP,
                8,
                8,
                GROUP_DELAY_NANOS,
                commits -> {
                    assertEquals(2, commits.size());
                    throw new IllegalStateException("injected shared status force failure");
                });
        DelosStorageTransaction first = table.beginTransaction();
        DelosStorageTransaction second = table.beginTransaction();
        table.insert(1L, emptyRow(), first);
        table.insert(2L, emptyRow(), second);

        List<Throwable> failures = commitTogetherExpectingFailure(table, first, second);
        assertEquals(2, failures.size());
        assertTrue(failures.stream().allMatch(failure ->
                failure instanceof IllegalStateException
                        && failure.getMessage().contains("shared status force failure")));
        assertEquals(0, table.logicalRowCountForTesting());

        table.abort(first);
        table.abort(second);
        table.close();

        MvccInheritedTable reopened = new MvccInheritedTable(0L, 901L, directory);
        try {
            assertEquals(0, reopened.logicalRowCountForTesting());
            reopened.assertConsistentForTesting();
        } finally {
            reopened.close();
        }
    }

    @Test
    void preparationFailureDoesNotPreventAnotherTransactionFromCommitting() throws Exception {
        Path directory = databaseDirectory.resolve("partial-preparation-failure");
        Files.createDirectories(directory);
        MvccInheritedTable table = new MvccInheritedTable(0L, 902L, directory);
        DelosStorageTransaction invalid = table.beginTransaction();
        DelosStorageTransaction valid = table.beginTransaction();
        table.insert(1L, new StoreDataValue[] {new CloneableNonStorableStoreValue()}, invalid);
        table.insert(2L, emptyRow(), valid);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> invalidCommit = executor.submit(() -> commitAfterSignal(table, invalid, ready, start));
            Future<?> validCommit = executor.submit(() -> commitAfterSignal(table, valid, ready, start));
            assertTrue(ready.await(30L, TimeUnit.SECONDS));
            start.countDown();

            ExecutionException invalidFailure = assertThrows(
                    ExecutionException.class,
                    () -> invalidCommit.get(30L, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, invalidFailure.getCause());
            validCommit.get(30L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, table.logicalRowCountForTesting());
        table.assertConsistentForTesting();
        table.close();

        MvccInheritedTable reopened = new MvccInheritedTable(0L, 902L, directory);
        try {
            assertEquals(1, reopened.logicalRowCountForTesting());
            reopened.assertConsistentForTesting();
        } finally {
            reopened.close();
        }
    }

    @Test
    void backupSnapshotAndTableCloseDrainAlreadyEnrolledCommits() throws Exception {
        Path directory = databaseDirectory.resolve("backup-and-close");
        Files.createDirectories(directory);
        MvccInheritedTable table = new MvccInheritedTable(
                0L,
                903L,
                directory,
                MvccCommitCoordinator.Mode.GROUP,
                8,
                8,
                GROUP_DELAY_NANOS,
                MvccInheritedTable.SharedStatusForceHook.NOOP);
        DelosStorageTransaction first = table.beginTransaction();
        DelosStorageTransaction second = table.beginTransaction();
        for (long row = 1L; row <= 128L; row++) {
            table.insert(row, emptyRow(), first);
            table.insert(1_000L + row, emptyRow(), second);
        }

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> firstCommit;
        Future<?> secondCommit;
        Future<?> close;
        try (DelosStorageBackupCoordinator.Guard ignored =
                     DelosStorageBackupCoordinator.enterBackupSnapshot()) {
            firstCommit = executor.submit(() -> commitAfterSignal(table, first, ready, start));
            secondCommit = executor.submit(() -> commitAfterSignal(table, second, ready, start));
            assertTrue(ready.await(30L, TimeUnit.SECONDS));
            start.countDown();
            awaitEnrollmentDepth(table, 2);

            close = executor.submit(table::close);
            assertThrows(TimeoutException.class, () -> close.get(200L, TimeUnit.MILLISECONDS));
            assertFalse(close.isDone());
            assertEquals(0, table.logicalRowCountForTesting());
        }

        try {
            firstCommit.get(60L, TimeUnit.SECONDS);
            secondCommit.get(60L, TimeUnit.SECONDS);
            close.get(60L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        MvccInheritedTable reopened = new MvccInheritedTable(0L, 903L, directory);
        try {
            assertEquals(256, reopened.logicalRowCountForTesting());
            reopened.assertConsistentForTesting();
        } finally {
            reopened.close();
        }
    }

    @Test
    void closedCoordinatorRejectsNewSubmissions() {
        MvccCommitCoordinator<Integer, Integer> coordinator =
                new MvccCommitCoordinator<>(MvccCommitCoordinator.Mode.GROUP, 2);
        coordinator.close();

        assertTrue(coordinator.closedForTesting());
        assertThrows(
                MvccCommitCoordinator.CoordinatorClosedException.class,
                () -> coordinator.submit(1, false, items -> List.of(
                        MvccCommitCoordinator.Outcome.success(items.getFirst()))));
    }

    private static List<Throwable> commitTogetherExpectingFailure(
            MvccInheritedTable table,
            DelosStorageTransaction first,
            DelosStorageTransaction second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> firstCommit = executor.submit(() -> commitAfterSignal(table, first, ready, start));
            Future<?> secondCommit = executor.submit(() -> commitAfterSignal(table, second, ready, start));
            assertTrue(ready.await(30L, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                    failureOf(firstCommit),
                    failureOf(secondCommit));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> future.get(30L, TimeUnit.SECONDS));
        return failure.getCause();
    }

    private static void commitAfterSignal(
            MvccInheritedTable table,
            DelosStorageTransaction transaction,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            assertTrue(start.await(30L, TimeUnit.SECONDS));
            table.commit(transaction);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted before commit", interrupted);
        }
    }

    private static void awaitEnrollmentDepth(MvccInheritedTable table, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
        while (System.nanoTime() < deadline) {
            if (table.durabilityEnrollmentCountForTesting() >= expected) {
                return;
            }
            Thread.sleep(1L);
        }
        throw new AssertionError("durability enrollment depth did not reach " + expected);
    }

    private static StoreDataValue[] emptyRow() {
        return new StoreDataValue[0];
    }

    private static final class CloneableNonStorableStoreValue implements StoreValueOperations {
        @Override
        public StoreDataValue cloneHolder() {
            return new CloneableNonStorableStoreValue();
        }

        @Override
        public StoreDataValue cloneValue(boolean forceMaterialization) {
            return new CloneableNonStorableStoreValue();
        }

        @Override
        public StoreDataValue getNewNull() {
            return new CloneableNonStorableStoreValue();
        }

        @Override
        public StoreDataValue recycle() {
            return this;
        }

        @Override
        public int getLength() {
            return 0;
        }

        @Override
        public long getLong() {
            return 0L;
        }

        @Override
        public String getString() {
            return "invalid";
        }

        @Override
        public boolean isNull() {
            return false;
        }

        @Override
        public Object getObject() {
            return null;
        }

        @Override
        public InputStream getStream() {
            return null;
        }

        @Override
        public int estimateMemoryUsage() {
            return 0;
        }

        @Override
        public void setValue(StoreDataValue source) {
        }

        @Override
        public void setIntValue(int value) {
        }

        @Override
        public void setLongValue(long value) {
        }

        @Override
        public void restoreToNull() {
        }

        @Override
        public void readExternal(ObjectInput input) {
        }

        @Override
        public void readExternalFromArray(ArrayInputStream input) {
        }

        @Override
        public void writeExternal(ObjectOutput output) throws IOException {
        }

        @Override
        public int compare(StoreDataValue other) {
            return 0;
        }

        @Override
        public int compare(StoreDataValue other, boolean nullsOrderedLow) {
            return 0;
        }

        @Override
        public boolean compare(
                int op,
                StoreDataValue other,
                boolean orderedNulls,
                boolean unknownRV) {
            return false;
        }

        @Override
        public boolean compare(
                int op,
                StoreDataValue other,
                boolean orderedNulls,
                boolean nullsOrderedLow,
                boolean unknownRV) {
            return false;
        }
    }
}
