/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.services.locks;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.services.locks.C_LockFactory;
import org.apache.derby.iapi.services.locks.CompatibilitySpace;
import org.apache.derby.iapi.services.locks.LockOwner;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.RowLock;
import org.apache.derby.impl.store.raw.data.RecordId;

/** Package-private correctness proofs for the adaptive RecordId RS2 fast path. */
public final class RecordReadFastPathTestSupport {
    private static final String ENABLE_PROPERTY =
            "delosdb.experimental.fastRecordReadLock";

    private RecordReadFastPathTestSupport() {
    }

    public static void verifyMaterializationBeforeWriter() throws Exception {
        enableDiagnostics();
        ConcurrentLockSet table = new ConcurrentLockSet(null);
        CompatibilitySpace readerA = space();
        CompatibilitySpace readerB = space();
        CompatibilitySpace writer = space();
        RecordId row = row(41);

        Lock first = requireLock(table.lockObject(
                readerA, row, RowLock.RS2, C_LockFactory.NO_WAIT),
                "first RS2 reader was not granted");
        Lock second = requireLock(table.lockObject(
                readerB, row, RowLock.RS2, C_LockFactory.NO_WAIT),
                "second compatible RS2 reader was not granted");

        if (table.lockObject(writer, row, RowLock.RX2, C_LockFactory.NO_WAIT) != null) {
            throw new AssertionError("RX2 was granted while RS2 readers were active");
        }

        table.unlock(first, 1);
        table.unlock(second, 1);

        Lock exclusive = requireLock(table.lockObject(
                writer, row, RowLock.RX2, C_LockFactory.NO_WAIT),
                "RX2 was not granted after both RS2 readers released");
        table.unlock(exclusive, 1);
    }

    public static void verifyRepeatedReaderMaterializes() throws Exception {
        enableDiagnostics();
        ConcurrentLockSet table = new ConcurrentLockSet(null);
        CompatibilitySpace readerA = space();
        CompatibilitySpace readerB = space();
        RecordId row = row(44);

        Lock first = requireLock(table.lockObject(
                readerA, row, RowLock.RS2, C_LockFactory.NO_WAIT),
                "first RS2 reader was not granted");
        Lock second = requireLock(table.lockObject(
                readerB, row, RowLock.RS2, C_LockFactory.NO_WAIT),
                "second compatible RS2 reader was not granted");
        Lock repeated = requireLock(table.lockObject(
                readerA, row, RowLock.RS2, C_LockFactory.NO_WAIT),
                "repeated RS2 reader was not granted after materialization");
        if (repeated.getCount() != 2) {
            throw new AssertionError(
                    "repeated RS2 count was not preserved: " + repeated.getCount());
        }

        table.unlock(repeated, 1);
        table.unlock(first, 1);
        table.unlock(second, 1);
        if (!table.shallowClone().isEmpty()) {
            throw new AssertionError("repeated RS2 materialization left lock-table state");
        }
    }

    public static void verifyWriterWaiterOrdering() throws Exception {
        enableDiagnostics();
        ConcurrentLockSet table = new ConcurrentLockSet(null);
        CompatibilitySpace readerA = space();
        CompatibilitySpace readerB = space();
        CompatibilitySpace lateReader = space();
        CompatibilitySpace writer = space();
        RecordId row = row(42);

        Lock first = requireLock(table.lockObject(
                readerA, row, RowLock.RS2, C_LockFactory.NO_WAIT),
                "first RS2 reader was not granted");
        Lock second = requireLock(table.lockObject(
                readerB, row, RowLock.RS2, C_LockFactory.NO_WAIT),
                "second compatible RS2 reader was not granted");

        AtomicReference<Lock> writerLock = new AtomicReference<Lock>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        CountDownLatch writerDone = new CountDownLatch(1);
        Thread writerThread = new Thread(() -> {
            try {
                writerLock.set(table.lockObject(
                        writer, row, RowLock.RX2, C_LockFactory.WAIT_FOREVER));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                writerDone.countDown();
            }
        }, "record-read-fast-path-writer");
        writerThread.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!table.anyoneBlocked() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (!table.anyoneBlocked()) {
            throw new AssertionError("writer did not enter the lock wait queue");
        }

        if (table.lockObject(
                lateReader, row, RowLock.RS2, C_LockFactory.NO_WAIT) != null) {
            throw new AssertionError("late RS2 reader bypassed the queued RX2 writer");
        }

        table.unlock(first, 1);
        table.unlock(second, 1);
        if (!writerDone.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("queued RX2 writer did not wake after readers released");
        }
        if (failure.get() != null) {
            throw new AssertionError("queued RX2 writer failed", failure.get());
        }
        Lock grantedWriter = requireLock(writerLock.get(), "queued RX2 writer was not granted");
        table.unlock(grantedWriter, 1);

        Lock reader = requireLock(table.lockObject(
                lateReader, row, RowLock.RS2, C_LockFactory.NO_WAIT),
                "RS2 reader was not granted after writer released");
        table.unlock(reader, 1);
    }

    public static void verifyConcurrentReadersRemainReusable() throws Exception {
        enableDiagnostics();
        ConcurrentLockSet.resetHotStateDiagnosticsForTesting();
        final int threads = 8;
        final int iterations = 20_000;
        ConcurrentLockSet table = new ConcurrentLockSet(null);
        RecordId row = row(43);
        CompatibilitySpace[] spaces = new CompatibilitySpace[threads];
        for (int i = 0; i < threads; i++) {
            spaces[i] = space();
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        for (int thread = 0; thread < threads; thread++) {
            final CompatibilitySpace reader = spaces[thread];
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        Lock lock = table.lockObject(
                                reader, row, RowLock.RS2, C_LockFactory.NO_WAIT);
                        if (lock == null) {
                            throw new AssertionError("compatible RS2 request blocked");
                        }
                        table.unlock(lock, 1);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }, "record-read-fast-path-reader-" + thread);
            worker.start();
        }

        start.countDown();
        if (!done.await(15, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent RS2 readers did not finish");
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent RS2 proof failed", failure.get());
        }

        long promotionsBeforeReuse = hotStateMetric(
                ConcurrentLockSet.snapshotHotStateDiagnosticsForTesting(),
                "FastRecordReadControl", "promotions");
        long hitsBeforeReuse = hotStateMetric(
                ConcurrentLockSet.snapshotHotStateDiagnosticsForTesting(),
                "FastRecordReadControl", "acquireHits");
        if (promotionsBeforeReuse == 0L || hitsBeforeReuse == 0L) {
            throw new AssertionError(
                    "adaptive RecordId fast path did not enter: promotions="
                            + promotionsBeforeReuse + " acquireHits=" + hitsBeforeReuse);
        }

        for (int i = 0; i < 10_000; i++) {
            CompatibilitySpace reader = spaces[i & (threads - 1)];
            Lock lock = requireLock(table.lockObject(
                    reader, row, RowLock.RS2, C_LockFactory.NO_WAIT),
                    "dormant RS2 fast control was not reusable");
            table.unlock(lock, 1);
        }
        String[] afterReuse = ConcurrentLockSet.snapshotHotStateDiagnosticsForTesting();
        long promotionsAfterReuse = hotStateMetric(
                afterReuse, "FastRecordReadControl", "promotions");
        long hitsAfterReuse = hotStateMetric(
                afterReuse, "FastRecordReadControl", "acquireHits");
        if (promotionsAfterReuse != promotionsBeforeReuse) {
            throw new AssertionError(
                    "dormant RecordId fast control re-promoted: before="
                            + promotionsBeforeReuse + " after=" + promotionsAfterReuse);
        }
        if (hitsAfterReuse - hitsBeforeReuse != 10_000L) {
            throw new AssertionError(
                    "dormant RecordId fast control did not serve all reuses: hits before="
                            + hitsBeforeReuse + " after=" + hitsAfterReuse);
        }

        Lock exclusive = requireLock(table.lockObject(
                space(), row, RowLock.RX2, C_LockFactory.NO_WAIT),
                "RX2 was not granted after dormant fast control materialized");
        table.unlock(exclusive, 1);
        Map<?, ?> remaining = table.shallowClone();
        if (!remaining.isEmpty()) {
            throw new AssertionError(
                    "writer materialization left lock-table state: " + remaining);
        }
    }

    private static void enableDiagnostics() {
        System.setProperty(ENABLE_PROPERTY, "true");
        System.setProperty("delosdb.diagnostic.hotState", "true");
    }

    private static RecordId row(int recordId) {
        return new RecordId(new ContainerKey(0, 1701), 1L, recordId);
    }

    private static CompatibilitySpace space() {
        return new LockSpace(new TestLockOwner());
    }

    private static long hotStateMetric(String[] rows, String component, String metric) {
        for (String row : rows) {
            String[] fields = row.split(",", -1);
            if (fields.length == 3 && component.equals(fields[0]) && metric.equals(fields[1])) {
                return Long.parseLong(fields[2]);
            }
        }
        throw new AssertionError("missing hot-state metric " + component + "." + metric);
    }

    private static Lock requireLock(Lock lock, String message) {
        if (lock == null) {
            throw new AssertionError(message);
        }
        return lock;
    }

    private static final class TestLockOwner implements LockOwner {
        public boolean noWait() {
            return false;
        }

        public boolean isNestedOwner() {
            return false;
        }

        public boolean nestsUnder(LockOwner other) {
            return false;
        }
    }
}
