package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;

/** Describes the snapshot horizon used by a durable MVCC vacuum pass. */
public record MvccVacuumPlan(MvccCommitSequence oldestVisibleThrough) {
    public MvccVacuumPlan {
        oldestVisibleThrough = Objects.requireNonNull(oldestVisibleThrough, "oldestVisibleThrough");
        if (oldestVisibleThrough.equals(MvccCommitSequence.NONE)) {
            throw new IllegalArgumentException("vacuum horizon must be a committed sequence");
        }
    }

    public static MvccVacuumPlan through(long commitSequence) {
        return new MvccVacuumPlan(new MvccCommitSequence(commitSequence));
    }
}
