package io.github.ggeorg.delosdb.storage.io.volume;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Deterministic I/O-level fault decorator for Delos page volumes.
 *
 * <p>This decorator owns only storage I/O failure injection. It can fail the
 * n-th complete-page write or the n-th force boundary. It deliberately carries
 * no transaction, commit/abort, MVCC visibility, outcome-log, recovery-policy,
 * SQL, heap, or provider semantics.</p>
 */
public final class FaultInjectingPageVolume implements DelosPageVolume {
    private final ReentrantLock lock = new ReentrantLock();
    private final DelosPageVolume delegate;
    private final FaultSchedule faultSchedule;
    private long writeCount;
    private long forceCount;

    private FaultInjectingPageVolume(DelosPageVolume delegate, FaultSchedule faultSchedule) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.faultSchedule = Objects.requireNonNull(faultSchedule, "faultSchedule");
    }

    public static FaultInjectingPageVolume wrap(DelosPageVolume delegate, FaultSchedule faultSchedule) {
        return new FaultInjectingPageVolume(delegate, faultSchedule);
    }

    public static FaultInjectingPageVolume wrap(DelosPageVolume delegate) {
        return wrap(delegate, FaultSchedule.none());
    }

    @Override
    public DelosPage readPage(DelosPageId id) throws IOException {
        lock.lock();
        try {
            return delegate.readPage(id);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writePage(DelosPage page) throws IOException {
        lock.lock();
        try {
            long operation = ++writeCount;
            if (faultSchedule.shouldFailWrite(operation)) {
                throw new IOException("injected Delos page-volume write failure at write #" + operation);
            }
            delegate.writePage(page);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public DelosPage allocatePage(int pageType) throws IOException {
        lock.lock();
        try {
            return delegate.allocatePage(pageType);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long pageCount() throws IOException {
        lock.lock();
        try {
            return delegate.pageCount();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void force() throws IOException {
        lock.lock();
        try {
            long operation = ++forceCount;
            if (faultSchedule.shouldFailForce(operation)) {
                throw new IOException("injected Delos page-volume force failure at force #" + operation);
            }
            delegate.force();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public SyncPolicy syncPolicy() {
        return delegate.syncPolicy();
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            delegate.close();
        } finally {
            lock.unlock();
        }
    }

    /** Immutable schedule for deterministic I/O-level page-volume faults. */
    public static final class FaultSchedule {
        private static final long NO_FAULT = -1L;

        private final long failOnWrite;
        private final long failOnForce;

        private FaultSchedule(long failOnWrite, long failOnForce) {
            this.failOnWrite = failOnWrite;
            this.failOnForce = failOnForce;
        }

        public static FaultSchedule none() {
            return new FaultSchedule(NO_FAULT, NO_FAULT);
        }

        public static FaultSchedule failOnWrite(long writeNumber) {
            return none().withFailOnWrite(writeNumber);
        }

        public static FaultSchedule failOnForce(long forceNumber) {
            return none().withFailOnForce(forceNumber);
        }

        public static FaultSchedule of(long failOnWrite, long failOnForce) {
            return new FaultSchedule(checkedFaultNumber(failOnWrite, "failOnWrite"),
                    checkedFaultNumber(failOnForce, "failOnForce"));
        }

        public FaultSchedule withFailOnWrite(long writeNumber) {
            return new FaultSchedule(checkedOperationNumber(writeNumber, "writeNumber"), failOnForce);
        }

        public FaultSchedule withFailOnForce(long forceNumber) {
            return new FaultSchedule(failOnWrite, checkedOperationNumber(forceNumber, "forceNumber"));
        }

        public long failOnWrite() {
            return failOnWrite;
        }

        public long failOnForce() {
            return failOnForce;
        }

        boolean shouldFailWrite(long writeNumber) {
            return failOnWrite == writeNumber;
        }

        boolean shouldFailForce(long forceNumber) {
            return failOnForce == forceNumber;
        }

        private static long checkedOperationNumber(long value, String label) {
            if (value <= 0L) {
                throw new IllegalArgumentException(label + " must be positive: " + value);
            }
            return value;
        }

        private static long checkedFaultNumber(long value, String label) {
            if (value == NO_FAULT) {
                return value;
            }
            if (value <= 0L) {
                throw new IllegalArgumentException(label + " must be positive or -1 for no fault: " + value);
            }
            return value;
        }
    }
}
