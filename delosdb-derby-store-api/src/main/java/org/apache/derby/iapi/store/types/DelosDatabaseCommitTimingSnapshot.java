/*

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.types;

/**
 * Provider-neutral timing evidence for mixed raw-store/MVCC commits.
 *
 * <p>The raw-decision interval measures only the synchronous Derby log force
 * that makes the transaction decision durable. The publication interval
 * measures only participant publication after that decision.</p>
 */
public record DelosDatabaseCommitTimingSnapshot(
        long rawDecisionForceSamples,
        long rawDecisionForceTotalNanos,
        long rawDecisionForceMaxNanos,
        long participantPublicationSamples,
        long participantPublicationTotalNanos,
        long participantPublicationMaxNanos) {

    public DelosDatabaseCommitTimingSnapshot {
        validate("raw decision force", rawDecisionForceSamples,
                rawDecisionForceTotalNanos, rawDecisionForceMaxNanos);
        validate("participant publication", participantPublicationSamples,
                participantPublicationTotalNanos, participantPublicationMaxNanos);
    }

    public static final DelosDatabaseCommitTimingSnapshot EMPTY =
            new DelosDatabaseCommitTimingSnapshot(0L, 0L, 0L, 0L, 0L, 0L);

    public long rawDecisionForceAverageNanos() {
        return average(rawDecisionForceTotalNanos, rawDecisionForceSamples);
    }

    public long participantPublicationAverageNanos() {
        return average(participantPublicationTotalNanos, participantPublicationSamples);
    }

    private static long average(long total, long samples) {
        return samples == 0L ? 0L : total / samples;
    }

    private static void validate(String label, long samples, long total, long maximum) {
        if (samples < 0L || total < 0L || maximum < 0L) {
            throw new IllegalArgumentException(label + " timing values must be non-negative");
        }
        if (samples == 0L && (total != 0L || maximum != 0L)) {
            throw new IllegalArgumentException(label + " timing without samples");
        }
        if (maximum > total) {
            throw new IllegalArgumentException(label + " maximum exceeds total");
        }
    }
}
