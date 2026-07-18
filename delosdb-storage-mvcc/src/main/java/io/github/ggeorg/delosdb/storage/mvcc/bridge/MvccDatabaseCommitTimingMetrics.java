package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.apache.derby.iapi.store.types.DelosDatabaseCommitTimingSnapshot;

/**
 * Database-scoped timing counters for coordinated commits.
 *
 * <p>Collection is disabled by default and begins only after an explicit
 * diagnostics reset, keeping normal commits free of counter contention.</p>
 */
final class MvccDatabaseCommitTimingMetrics {
    private final LongAdder rawDecisionSamples = new LongAdder();
    private final LongAdder rawDecisionTotalNanos = new LongAdder();
    private final AtomicLong rawDecisionMaxNanos = new AtomicLong();
    private final LongAdder publicationSamples = new LongAdder();
    private final LongAdder publicationTotalNanos = new LongAdder();
    private final AtomicLong publicationMaxNanos = new AtomicLong();
    private volatile boolean enabled;

    void recordRawDecisionForce(long elapsedNanos) {
        if (enabled) {
            record(elapsedNanos, rawDecisionSamples, rawDecisionTotalNanos, rawDecisionMaxNanos);
        }
    }

    void recordParticipantPublication(long elapsedNanos) {
        if (enabled) {
            record(elapsedNanos, publicationSamples, publicationTotalNanos, publicationMaxNanos);
        }
    }

    boolean enabled() {
        return enabled;
    }

    DelosDatabaseCommitTimingSnapshot snapshot() {
        return new DelosDatabaseCommitTimingSnapshot(
                rawDecisionSamples.sum(),
                rawDecisionTotalNanos.sum(),
                rawDecisionMaxNanos.get(),
                publicationSamples.sum(),
                publicationTotalNanos.sum(),
                publicationMaxNanos.get());
    }

    void reset() {
        rawDecisionSamples.reset();
        rawDecisionTotalNanos.reset();
        rawDecisionMaxNanos.set(0L);
        publicationSamples.reset();
        publicationTotalNanos.reset();
        publicationMaxNanos.set(0L);
        enabled = true;
    }

    private static void record(
            long elapsedNanos,
            LongAdder samples,
            LongAdder total,
            AtomicLong maximum) {
        long normalized = Math.max(0L, elapsedNanos);
        samples.increment();
        total.add(normalized);
        maximum.accumulateAndGet(normalized, Math::max);
    }
}
