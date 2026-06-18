package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

/**
 * Retained MVCC snapshot handle.
 *
 * <p>A plain {@link MvccSnapshot} is an immutable view. A lease additionally
 * tells the transaction manager that vacuum must retain history needed by that
 * view until the lease is closed. This keeps the proof model honest: an old
 * snapshot can either be protected by a watermark, or later fail loudly via the
 * missing-history boundary added in A44.</p>
 */
public final class MvccSnapshotLease implements AutoCloseable {
    private final MvccSnapshot snapshot;
    private final Runnable closeAction;
    private boolean closed;

    MvccSnapshotLease(MvccSnapshot snapshot, Runnable closeAction) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    public MvccSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeAction.run();
    }
}
