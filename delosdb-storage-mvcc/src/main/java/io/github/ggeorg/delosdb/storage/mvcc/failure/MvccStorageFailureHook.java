package io.github.ggeorg.delosdb.storage.mvcc.failure;

import java.util.Objects;

/**
 * Internal capability passed from the database-owned failure registry to
 * low-level MVCC storage stages.
 *
 * <p>This is not a provider, SQL, property, or application configuration
 * surface. Normal stores pass {@link #NOOP}; only the package-private bridge
 * test/research construction path can supply an active implementation.</p>
 */
@FunctionalInterface
public interface MvccStorageFailureHook {
    MvccStorageFailureHook NOOP = (point, context) -> { };

    void hit(Point point, Context context);

    enum Point {
        DURING_CHECKPOINT,
        DURING_PAGE_ALLOCATION,
        DURING_OVERFLOW_PUBLICATION,
        DURING_VACUUM_REUSE
    }

    record Context(
            long transactionId,
            long commitSequence,
            int participantIndex,
            int participantCount) {
        public Context {
            if (transactionId <= 0L) {
                throw new IllegalArgumentException(
                        "transactionId must be positive: " + transactionId);
            }
            if (commitSequence <= 0L) {
                throw new IllegalArgumentException(
                        "commitSequence must be positive: " + commitSequence);
            }
            if (participantIndex <= 0) {
                throw new IllegalArgumentException(
                        "participantIndex must be positive: " + participantIndex);
            }
            if (participantCount < participantIndex) {
                throw new IllegalArgumentException(
                        "participantCount must include participantIndex: index="
                                + participantIndex + ", count=" + participantCount);
            }
        }

        public static Context transaction(
                long transactionId,
                long commitSequence,
                int participantIndex,
                int participantCount) {
            return new Context(
                    transactionId,
                    commitSequence,
                    participantIndex,
                    participantCount);
        }
    }

    static MvccStorageFailureHook require(MvccStorageFailureHook hook) {
        return Objects.requireNonNullElse(hook, NOOP);
    }
}
