/*

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.derby.iapi.store.types.DelosDatabaseCommitTimingSnapshot;
import org.junit.jupiter.api.Test;

/** Verifies the database-scoped split decision/publication timing counters. */
final class MvccDatabaseCommitTimingMetricsTest {
    @Test
    void recordsIndependentDecisionAndPublicationIntervalsAndResets() {
        MvccDatabaseCommitTimingMetrics metrics = new MvccDatabaseCommitTimingMetrics();
        metrics.recordRawDecisionForce(99L);
        metrics.recordParticipantPublication(99L);
        assertEquals(DelosDatabaseCommitTimingSnapshot.EMPTY, metrics.snapshot());

        metrics.reset();
        metrics.recordRawDecisionForce(10L);
        metrics.recordRawDecisionForce(30L);
        metrics.recordParticipantPublication(7L);
        metrics.recordParticipantPublication(13L);

        DelosDatabaseCommitTimingSnapshot snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.rawDecisionForceSamples());
        assertEquals(40L, snapshot.rawDecisionForceTotalNanos());
        assertEquals(20L, snapshot.rawDecisionForceAverageNanos());
        assertEquals(30L, snapshot.rawDecisionForceMaxNanos());
        assertEquals(2L, snapshot.participantPublicationSamples());
        assertEquals(20L, snapshot.participantPublicationTotalNanos());
        assertEquals(10L, snapshot.participantPublicationAverageNanos());
        assertEquals(13L, snapshot.participantPublicationMaxNanos());

        metrics.reset();
        assertEquals(DelosDatabaseCommitTimingSnapshot.EMPTY, metrics.snapshot());
    }

    @Test
    void normalizesNegativeIntervalsWithoutDroppingSamples() {
        MvccDatabaseCommitTimingMetrics metrics = new MvccDatabaseCommitTimingMetrics();
        metrics.reset();

        metrics.recordRawDecisionForce(-1L);
        metrics.recordParticipantPublication(-1L);

        DelosDatabaseCommitTimingSnapshot snapshot = metrics.snapshot();
        assertEquals(1L, snapshot.rawDecisionForceSamples());
        assertEquals(0L, snapshot.rawDecisionForceTotalNanos());
        assertEquals(1L, snapshot.participantPublicationSamples());
        assertEquals(0L, snapshot.participantPublicationTotalNanos());
    }
}
