package io.github.ggeorg.delosdb.storage.mvcc.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MvccSubsystemRecoveryRecordStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void subsystemRecoveryRecordsRoundTripAndResumeSequence() throws Exception {
        MvccSubsystemRecoveryRecordStore store = MvccSubsystemRecoveryRecordStore.open(
                tempDir, "conglomerate-0-7");
        store.appendRowPageRedo(11L, 22L, 3L, 4L);
        store.appendIndexPageRedo(1L, 2L);
        store.appendOverflowPageRedo(5L, 64000L);
        store.appendFreeSpaceMapRedo(3L, 9L);
        store.appendTransactionOutcomeRedo(11L, 22L);
        store.appendCheckpoint(4L, 2L);

        MvccSubsystemRecoveryRecordStore.Diagnostics diagnostics = store.diagnostics();
        assertTrue(Files.exists(diagnostics.path()));
        assertEquals(6L, diagnostics.recordCount());
        assertEquals(6L, diagnostics.lastSequence());
        assertTrue(diagnostics.has(MvccSubsystemRecoveryRecordStore.Subsystem.ROW_PAGE));
        assertTrue(diagnostics.has(MvccSubsystemRecoveryRecordStore.Subsystem.INDEX_PAGE));
        assertTrue(diagnostics.has(MvccSubsystemRecoveryRecordStore.Subsystem.OVERFLOW_PAGE));
        assertTrue(diagnostics.has(MvccSubsystemRecoveryRecordStore.Subsystem.FREE_SPACE_MAP));
        assertTrue(diagnostics.has(MvccSubsystemRecoveryRecordStore.Subsystem.TRANSACTION_OUTCOME));
        assertTrue(diagnostics.has(MvccSubsystemRecoveryRecordStore.Subsystem.CHECKPOINT));
        assertFalse(diagnostics.summaries().isEmpty());

        MvccSubsystemRecoveryRecordStore reopened = MvccSubsystemRecoveryRecordStore.open(
                tempDir, "conglomerate-0-7");
        reopened.appendCheckpoint(4L, 2L);
        assertEquals(7L, reopened.diagnostics().lastSequence());
        assertEquals(2L, reopened.diagnostics().count(MvccSubsystemRecoveryRecordStore.Subsystem.CHECKPOINT));
    }

    @Test
    void maintenanceOnlyRecordsDoNotPretendToBePartialTransactionReplay() {
        MvccSubsystemRecoveryRecordStore store = MvccSubsystemRecoveryRecordStore.open(
                tempDir, "conglomerate-0-8");
        store.appendIndexPageRedo(1L, 2L);
        store.appendCheckpoint(4L, 2L);

        store.replayPlan().requireCrossSubsystemCompleteness(java.util.Set.of(
                MvccSubsystemRecoveryRecordStore.Subsystem.ROW_PAGE,
                MvccSubsystemRecoveryRecordStore.Subsystem.INDEX_PAGE,
                MvccSubsystemRecoveryRecordStore.Subsystem.OVERFLOW_PAGE,
                MvccSubsystemRecoveryRecordStore.Subsystem.FREE_SPACE_MAP,
                MvccSubsystemRecoveryRecordStore.Subsystem.TRANSACTION_OUTCOME));
    }
}
